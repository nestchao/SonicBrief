from __future__ import annotations

import datetime
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

import ctranslate2
import yt_dlp
from faster_whisper import WhisperModel
from faster_whisper.audio import decode_audio

import config
import download_settings
import gemini_settings
import groq_settings
import storage

_MODEL_CACHE: dict[tuple[str, str, str], WhisperModel] = {}
_MODEL_LOCK = threading.Lock()
_DIARIZATION_PIPELINE: Any = None
_DIARIZATION_LOCK = threading.Lock()
_CANCEL_EVENTS: dict[str, threading.Event] = {}
_CANCEL_LOCK = threading.Lock()


class JobCancelled(Exception):
    """Raised when a user asks an active pipeline to stop."""


def register_job(job_id: str) -> None:
    with _CANCEL_LOCK:
        _CANCEL_EVENTS[job_id] = threading.Event()

def request_cancel(job_id: str) -> bool:
    with _CANCEL_LOCK:
        event = _CANCEL_EVENTS.get(job_id)
        if event is None:
            return False
        event.set()
        return True

def is_cancelled(job_id: str) -> bool:
    with _CANCEL_LOCK:
        event = _CANCEL_EVENTS.get(job_id)
    return event.is_set() if event is not None else False

def clear_job(job_id: str) -> None:
    with _CANCEL_LOCK:
        _CANCEL_EVENTS.pop(job_id, None)

def check_cancelled(job_id: str) -> None:
    if is_cancelled(job_id):
        raise JobCancelled("Processing was cancelled by the user.")


# A stuck native call (GPU that cannot execute, dead driver, stalled transfer) never
# raises, so the job would sit at "processing" forever. Fail it with an explanation
# instead, so the interface shows a real error and the queue can move on.
STALL_TIMEOUT_SECONDS = int(os.getenv("SONICBRIEF_STALL_SECONDS", "900"))


def stalled_seconds(updated_at: Any, now: "datetime.datetime | None" = None) -> float | None:
    """Seconds since the job row last changed, or None when the timestamp is unusable."""
    if not updated_at:
        return None
    try:
        stamp = datetime.datetime.fromisoformat(str(updated_at))
    except (TypeError, ValueError):
        return None
    if stamp.tzinfo is None:
        stamp = stamp.replace(tzinfo=datetime.timezone.utc)
    current = now or datetime.datetime.now(datetime.timezone.utc)
    return max(0.0, (current - stamp).total_seconds())


def is_stalled(record: dict[str, Any], timeout: float, now: "datetime.datetime | None" = None) -> bool:
    """True when an in-flight job has not reported progress for longer than `timeout`."""
    if record.get("status") != "processing":
        return False
    idle = stalled_seconds(record.get("updated_at"), now)
    return idle is not None and idle > timeout


def stall_message(seconds: float) -> str:
    minutes = max(1, round(seconds / 60))
    reason = cuda_unavailable_reason()
    hint = f" {reason}" if reason else (
        " GPU acceleration is the usual cause: install the CUDA pack that matches the GPU build, "
        "or set SONICBRIEF_FORCE_CPU=1 in settings.env."
    )
    return (
        f"Processing stalled: no progress for {minutes} minutes, so SonicBrief stopped waiting."
        f"{hint} Run the job again."
    )


class StallWatchdog:
    """Fails a job whose progress stops advancing for STALL_TIMEOUT_SECONDS."""

    def __init__(self, job_id: str, timeout: float = STALL_TIMEOUT_SECONDS) -> None:
        self._job_id = job_id
        self._timeout = timeout
        self._stop = threading.Event()
        self._thread = threading.Thread(
            target=self._loop, name=f"stall-watch-{job_id[:8]}", daemon=True
        )

    def __enter__(self) -> "StallWatchdog":
        self.start()
        return self

    def __exit__(self, *_exc: object) -> bool:
        self.stop()
        return False

    def start(self) -> None:
        if self._timeout > 0 and not self._thread.is_alive():
            self._thread.start()

    def stop(self) -> None:
        self._stop.set()

    def _loop(self) -> None:
        while not self._stop.wait(5.0):
            try:
                record = storage.get_job(self._job_id)
            except KeyError:
                return
            except Exception:
                continue
            if not is_stalled(record, self._timeout):
                if record.get("status") != "processing":
                    return
                continue
            # Keep any transcript already produced: only the status changes.
            storage.update_job(
                self._job_id,
                status="failed",
                error=stall_message(stalled_seconds(record.get("updated_at")) or self._timeout),
                stage_detail=None,
            )
            return


_CUDA_LIB_CACHE: dict[str, str | None] = {}
# Set once a CUDA call has actually failed at runtime, so later jobs skip the GPU.
_CUDA_RUNTIME_FAILURE: str | None = None

_CUDA_MATH_LIBS = ("cublas64_12.dll", "cublasLt64_12.dll", "cudnn64_9.dll")


def _force_cpu_requested() -> bool:
    return os.getenv("SONICBRIEF_FORCE_CPU", "").strip().lower() in {"1", "true", "yes", "on"}


def _cuda_search_dirs() -> list[str]:
    """Folders where the CUDA pack may live, mirroring sonicbrief_server.prepare_environment."""
    dirs: list[str] = []
    local = os.environ.get("LOCALAPPDATA")
    if local:
        dirs.append(os.path.join(local, "SonicBrief", "cuda"))
    exe_dir = os.path.dirname(os.path.abspath(sys.executable))
    dirs.append(os.path.join(exe_dir, "cuda"))
    dirs.extend(part for part in os.environ.get("PATH", "").split(os.pathsep) if part)
    return dirs


def _find_cuda_library(name: str) -> str | None:
    """Absolute path of a CUDA DLL in the app's search folders, or None.

    Look the file up explicitly: `ctypes.WinDLL("cublas64_12.dll")` does not search PATH
    the way CTranslate2's own loader does, so a bare name reported a false "missing" and
    would have forced GPU users onto the CPU.
    """
    for folder in _cuda_search_dirs():
        candidate = os.path.join(folder, name)
        try:
            if os.path.isfile(candidate):
                return candidate
        except OSError:
            continue
    return None


def cuda_math_libs_error() -> str | None:
    """Report why CUDA kernels cannot run, or None when the math libraries load.

    `ctranslate2.get_cuda_device_count()` only asks the driver whether a GPU exists.
    A CPU install (or a GPU install without the CUDA pack) still reports a device while
    cuBLAS/cuDNN are absent, so CTranslate2 would pick CUDA and then fail on the first
    encode — historically leaving the job frozen forever. Check the libraries themselves.
    """
    if os.name != "nt":
        return None
    if "" not in _CUDA_LIB_CACHE:
        import ctypes

        error: str | None = None
        for name in _CUDA_MATH_LIBS:
            found = _find_cuda_library(name)
            if found is None:
                error = name
                break
            try:
                # Let the DLL resolve its own siblings (cublas <-> cublasLt <-> cudnn).
                os.add_dll_directory(os.path.dirname(found))
            except (AttributeError, OSError):
                pass
            try:
                ctypes.WinDLL(found)
            except OSError:
                error = name
                break
        _CUDA_LIB_CACHE[""] = error
    missing = _CUDA_LIB_CACHE[""]
    return f"{missing} is not installed (the CUDA pack is missing)" if missing else None


def cuda_unavailable_reason() -> str | None:
    """Human-readable reason GPU acceleration is off, or None when CUDA is usable."""
    if _force_cpu_requested():
        return "GPU acceleration is disabled by SONICBRIEF_FORCE_CPU."
    if _CUDA_RUNTIME_FAILURE is not None:
        return f"GPU acceleration failed earlier in this session: {_CUDA_RUNTIME_FAILURE}"
    libs_error = cuda_math_libs_error()
    if libs_error is not None:
        return f"GPU acceleration is unavailable because {libs_error}."
    return None


def cuda_available() -> bool:
    if _force_cpu_requested() or _CUDA_RUNTIME_FAILURE is not None:
        return False
    if cuda_math_libs_error() is not None:
        return False
    try:
        return ctranslate2.get_cuda_device_count() > 0
    except Exception:
        return False


def note_cuda_failure(reason: str) -> None:
    """Remember a runtime CUDA failure so subsequent jobs go straight to the CPU."""
    global _CUDA_RUNTIME_FAILURE
    if _CUDA_RUNTIME_FAILURE is None:
        _CUDA_RUNTIME_FAILURE = reason.strip()[:200]


def looks_like_cuda_failure(exc: BaseException) -> bool:
    text = f"{exc}".lower()
    return any(
        marker in text
        for marker in (
            "cublas", "cudnn", "cuda", "gpu", "no kernel image",
            "device-side assert", "cuda_error", "out of memory",
        )
    )


def ffmpeg_available() -> bool:
    return shutil.which("ffmpeg") is not None and shutil.which("ffprobe") is not None


def probe_audio_decode() -> tuple[bool, str | None]:
    """Decode a tiny generated WAV to catch faster-whisper/PyAV API incompatibilities."""
    import wave

    fd, temp_name = tempfile.mkstemp(prefix="sonicbrief-self-test-", suffix=".wav")
    os.close(fd)
    try:
        with wave.open(temp_name, "wb") as handle:
            handle.setnchannels(1)
            handle.setsampwidth(2)
            handle.setframerate(16000)
            handle.writeframes(b"\x00\x00" * 1600)
        decoded = decode_audio(temp_name, sampling_rate=16000)
        if len(decoded) == 0:
            return False, "Decoded self-test audio was empty."
        return True, None
    except Exception as exc:
        return False, str(exc)
    finally:
        Path(temp_name).unlink(missing_ok=True)


def get_model(model_name: str) -> tuple[WhisperModel, str, str]:
    if model_name not in config.ALLOWED_MODELS:
        raise ValueError("Unsupported Whisper model.")
    device = "cuda" if cuda_available() else "cpu"
    compute_type = "float16" if device == "cuda" else "int8"
    cache_key = (model_name, device, compute_type)
    with _MODEL_LOCK:
        if cache_key not in _MODEL_CACHE:
            _MODEL_CACHE[cache_key] = WhisperModel(model_name, device=device, compute_type=compute_type)
    return _MODEL_CACHE[cache_key], device, compute_type


_SOURCE_LABELS = {
    "youtube": "YouTube",
    "bilibili": "Bilibili",
    "vimeo": "Vimeo",
    "tiktok": "TikTok",
    "twitter": "X / Twitter",
    "soundcloud": "SoundCloud",
    "twitch": "Twitch",
    "douyin": "Douyin",
    "instagram": "Instagram",
    "facebook": "Facebook",
}


def source_label(source_type: str) -> str:
    return _SOURCE_LABELS.get(source_type, "Online media")


def classify_url(url: str) -> str:
    parsed = urlparse(url.strip())
    if parsed.scheme not in {"http", "https"}:
        raise ValueError("The link must start with http:// or https://.")
    host = (parsed.hostname or "").rstrip(".").lower()
    for domain, source_type in config.ALLOWED_URL_SOURCES.items():
        if host == domain or host.endswith("." + domain):
            return source_type
    raise ValueError(
        "Supported media links: YouTube, Bilibili, Vimeo, TikTok, X/Twitter, SoundCloud, "
        "Twitch, Douyin, Instagram, and Facebook."
    )


def probe_duration(path: Path) -> float | None:
    if not ffmpeg_available():
        return None
    result = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", str(path)],
        capture_output=True, text=True, check=False,
    )
    try:
        return float(result.stdout.strip())
    except (TypeError, ValueError):
        return None


def normalize_audio(source: Path, destination: Path) -> None:
    if not ffmpeg_available():
        raise RuntimeError("FFmpeg and FFprobe are required. Install FFmpeg and make sure it is in PATH.")
    result = subprocess.run(
        ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(source), "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", str(destination)],
        capture_output=True, text=True, check=False,
    )
    if result.returncode != 0:
        stderr = result.stderr.strip()
        lowered = stderr.lower()
        if "does not contain any stream" in lowered or "no audio" in lowered or "matches no streams" in lowered:
            raise RuntimeError("This media has no audio track, so there is nothing to transcribe.")
        raise RuntimeError(stderr or "FFmpeg could not decode this media file.")


_ANSI_ESCAPE_PATTERN = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")


def _download_error_message(error: Exception) -> str:
    message = _ANSI_ESCAPE_PATTERN.sub("", str(error)).strip()
    lowered = message.lower()
    if "bytes read" in lowered and "more expected" in lowered:
        return (
            "Media download was interrupted before the source finished sending the file. "
            "SonicBrief retried the transfer, but the Bilibili/CDN connection still ended early. "
            "Try the job again; if it repeats, update yt-dlp or try again later."
        )
    if (
        "unable to obtain file audio codec" in lowered
        or "does not contain any stream" in lowered
        or "matches no streams" in lowered
    ):
        return (
            "This media has no audio track, so there is nothing to transcribe. "
            "Silent videos, photo posts and some Instagram reels have no sound."
        )
    if "sign in to confirm you" in lowered and "not a bot" in lowered:
        return (
            "YouTube asked this computer to prove it is not a bot, so the download was refused. "
            "This is a check on your network, not on the video. Sign in to YouTube in Firefox (most reliable) "
            "and pick it in Settings → Downloads, or import a cookies.txt there. "
            "You can also download the video in your browser and use Upload instead."
        )
    if "failed to decrypt with dpapi" in lowered or "could not copy chrome cookie database" in lowered:
        return (
            "SonicBrief could not read the browser's cookies, which YouTube now requires. "
            "Fully close that browser (including background processes) and try again, or choose a different "
            "browser in Settings → Downloads. Firefox is the most reliable on Windows because Chrome and Edge "
            "encrypt their cookies in a way other programs often cannot read."
        )
    return f"Media download failed after retries: {message}"


def format_bytes(size: float) -> str:
    value = max(0.0, float(size or 0))
    for unit in ("B", "KB", "MB", "GB"):
        if value < 1024 or unit == "GB":
            return f"{value:.0f} {unit}" if unit == "B" else f"{value:.1f} {unit}"
        value /= 1024
    return f"{value:.1f} GB"


def download_audio(
    url: str,
    directory: Path,
    progress_callback: Any = None,
) -> tuple[Path, dict[str, Any]]:
    source_type = classify_url(url)
    output_template = str(directory / "source.%(ext)s")
    options = {
        "format": "bestaudio/best",
        "outtmpl": output_template,
        "noplaylist": True,
        "quiet": True,
        "no_warnings": True,
        "restrictfilenames": True,
        "continuedl": True,
        "retries": 10,
        "fragment_retries": 10,
        "extractor_retries": 3,
        "file_access_retries": 3,
        "postprocessors": [{"key": "FFmpegExtractAudio", "preferredcodec": "wav"}],
        "socket_timeout": 30,
    }
    if progress_callback:
        # Long downloads used to sit at "acquire 4%" for many minutes, which looks
        # like a hang. yt-dlp reports bytes here; throttle so we do not hammer the DB.
        state = {"last_emit": 0.0, "last_percent": -1.0}

        def _on_progress(status: dict[str, Any]) -> None:
            try:
                if status.get("status") == "finished":
                    progress_callback("Download complete · preparing audio…", 100.0)
                    return
                if status.get("status") != "downloading":
                    return
                done = float(status.get("downloaded_bytes") or 0)
                total = float(status.get("total_bytes") or status.get("total_bytes_estimate") or 0)
                percent = (done / total * 100.0) if total > 0 else 0.0
                now = time.monotonic()
                if percent - state["last_percent"] < 1.0 and now - state["last_emit"] < 3.0:
                    return
                state["last_emit"] = now
                state["last_percent"] = percent
                detail = f"Downloading media · {format_bytes(done)}"
                if total > 0:
                    detail += f" / {format_bytes(total)} ({percent:.0f}%)"
                speed = float(status.get("speed") or 0)
                if speed > 0:
                    detail += f" · {format_bytes(speed)}/s"
                eta = status.get("eta")
                if eta:
                    detail += f" · {format_duration(float(eta))} left"
                progress_callback(detail, percent)
            except Exception:
                return

        options["progress_hooks"] = [_on_progress]
    # Cookies are chosen per attempt below by `source_chain`; setting a browser
    # here would hand `auto` to yt-dlp as if it were a browser name.
    runtimes = download_settings.js_runtimes()
    if runtimes:
        options["js_runtimes"] = runtimes
    if source_type == "bilibili":
        # Bilibili media is commonly served from CDN endpoints that can close a
        # long response early. Smaller ranged requests make recovery/resume more
        # reliable without changing which video/audio stream yt-dlp selects.
        options["http_chunk_size"] = 5 * 1024 * 1024

    def _cookie_related(exc: Exception) -> bool:
        """Failures that a different cookie source could plausibly fix."""
        lowered = str(exc).lower()
        markers = (
            "not a bot",
            "sign in to confirm",
            "please sign in",
            "failed to decrypt",
            "dpapi",
            "cookie database",
            "cookies are no longer valid",
        )
        return any(marker in lowered for marker in markers)

    # Anonymous first, then whatever cookie sources this machine can offer.
    # `auto` only reaches for cookies after YouTube asks for them, so users who
    # never see a bot check never pay for this.
    sources: list[tuple[str, str] | None] = [None]
    for kind, value in download_settings.source_chain():
        sources.append((kind, value))

    info: dict[str, Any] | None = None
    last_error: Exception | None = None
    if progress_callback:
        progress_callback("Fetching media information…", 0.0)

    for index, source in enumerate(sources):
        attempt_options = dict(options)
        temporary_file: Path | None = None
        if source is not None:
            kind, value = source
            if kind == "browser":
                if value not in download_settings.BROWSERS:
                    continue
                attempt_options["cookiesfrombrowser"] = (value,)
                if progress_callback:
                    progress_callback(f"Using {value.capitalize()} cookies…", 0.0)
            else:
                # yt-dlp rewrites the jar, so hand it a throwaway copy.
                try:
                    temporary_file = Path(tempfile.mkstemp(prefix="sonicbrief-cookies-", suffix=".txt")[1])
                    shutil.copyfile(value, temporary_file)
                except OSError:
                    continue
                attempt_options["cookiefile"] = str(temporary_file)
                if progress_callback:
                    progress_callback("Using the imported cookies.txt…", 0.0)

        try:
            for retry in range(2):
                try:
                    with yt_dlp.YoutubeDL(attempt_options) as downloader:
                        info = downloader.extract_info(url, download=True)
                    break
                except yt_dlp.utils.DownloadError as exc:
                    last_error = exc
                    if _cookie_related(exc) or retry == 1:
                        raise
                    time.sleep(1.0)
            break
        except yt_dlp.utils.DownloadError as exc:
            last_error = exc
            if not _cookie_related(exc) or index == len(sources) - 1:
                break
            # Try the next cookie source.
            continue
        finally:
            if temporary_file is not None:
                temporary_file.unlink(missing_ok=True)

    if info is None:
        raise RuntimeError(_download_error_message(last_error or RuntimeError("Unknown download error."))) from last_error

    # Remember what worked so later jobs skip the sources that do not.
    if source is not None:
        download_settings.record_last_good("file" if source[0] == "file" else source[1])
    else:
        download_settings.record_last_good(None)

    candidates = sorted(
        (item for item in directory.glob("source.*") if not item.name.endswith(".part")),
        key=lambda item: item.stat().st_mtime,
        reverse=True,
    )
    if not candidates:
        raise RuntimeError("The downloader completed without producing a complete audio file.")
    metadata = {
        "title": info.get("title") or "Online video",
        "duration": info.get("duration"),
        "webpage_url": info.get("webpage_url") or url,
        "creator_name": info.get("channel") or info.get("uploader") or info.get("uploader_id"),
    }
    duration = float(metadata["duration"] or 0)
    if duration > config.MAX_MEDIA_DURATION_SECONDS:
        raise RuntimeError(f"This media is longer than the configured {config.MAX_MEDIA_DURATION_SECONDS // 3600}-hour limit.")
    return candidates[0], metadata


_SIMPLIFIED_CHINESE_PROMPT = "以下是普通话的对话内容，请使用简体中文转写。"
_CHINESE_LANGUAGE_PREFIXES = ("zh", "yue")


def is_chinese_language(code: Any) -> bool:
    return bool(code) and str(code).lower().startswith(_CHINESE_LANGUAGE_PREFIXES)


def detect_spoken_language(model: Any, path: Path) -> str | None:
    """Cheap probe (first two minutes) used only to decide the Chinese script hint.

    Whisper's Chinese output flips between Simplified and Traditional depending on
    the content, so we bias it explicitly instead of hoping.
    """
    probe_file: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as handle:
            probe_file = Path(handle.name)
        result = subprocess.run(
            [
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(path),
                "-t", "120", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", str(probe_file),
            ],
            capture_output=True, text=True, check=False, timeout=60,
        )
        if result.returncode != 0:
            return None
        from faster_whisper.audio import decode_audio

        audio = decode_audio(str(probe_file), sampling_rate=16000)
        language, probability, _ = model.detect_language(audio)
        return language if probability >= 0.5 else None
    except Exception:
        return None
    finally:
        if probe_file is not None:
            probe_file.unlink(missing_ok=True)


def local_transcribe(path: Path, model_name: str, language: str, progress_callback: Any = None) -> dict[str, Any]:
    if progress_callback:
        progress_callback("loading_model", 0, 0.0, f"Loading Whisper {model_name} model…")
    model, device, compute_type = get_model(model_name)
    target_language = None if language == "auto" else language
    initial_prompt: str | None = None
    if progress_callback:
        progress_callback("transcribing", 0, 0.0, f"Whisper is transcribing on {device.upper()} ({compute_type})…")
    if is_chinese_language(target_language):
        initial_prompt = _SIMPLIFIED_CHINESE_PROMPT
        if progress_callback:
            progress_callback("transcribing", 0, 0.0, "Chinese audio · transcribing in Simplified Chinese…")
    elif target_language is None:
        if is_chinese_language(detect_spoken_language(model, path)):
            initial_prompt = _SIMPLIFIED_CHINESE_PROMPT
            if progress_callback:
                progress_callback("transcribing", 0, 0.0, "Chinese audio detected · transcribing in Simplified Chinese…")
    segments_iter, info = model.transcribe(
        str(path),
        language=target_language,
        beam_size=5,
        vad_filter=True,
        word_timestamps=True,
        condition_on_previous_text=True,
        initial_prompt=initial_prompt,
    )
    segments: list[dict[str, Any]] = []
    logprobs: list[float] = []
    source_duration = float(getattr(info, "duration", 0) or 0)
    last_report = 0.0
    for segment in segments_iter:
        text = segment.text.strip()
        if not text:
            continue
        segments.append({
            "start": round(float(segment.start), 3),
            "end": round(float(segment.end), 3),
            "text": text,
            "speaker": None,
        })
        if segment.avg_logprob is not None:
            logprobs.append(float(segment.avg_logprob))
        if progress_callback:
            processed = min(float(segment.end), source_duration) if source_duration else float(segment.end)
            now = time.monotonic()
            if now - last_report >= 0.75 or (source_duration and processed >= source_duration):
                progress_callback(
                    "transcribing",
                    round((processed / source_duration) * 100) if source_duration else 0,
                    processed,
                    f"Transcribing audio · {format_duration(processed)} / {format_duration(source_duration)}",
                )
                last_report = now
    average_logprob = sum(logprobs) / len(logprobs) if logprobs else -99.0
    return {
        "segments": segments,
        "language": getattr(info, "language", language),
        "duration": float(getattr(info, "duration", 0) or 0),
        "average_logprob": average_logprob,
        "engine": f"local-whisper/{device}/{compute_type}",
    }


def format_duration(seconds: float) -> str:
    total = max(0, int(seconds))
    hours, remainder = divmod(total, 3600)
    minutes, secs = divmod(remainder, 60)
    return f"{hours}:{minutes:02d}:{secs:02d}" if hours else f"{minutes}:{secs:02d}"


def transcript_needs_fallback(result: dict[str, Any]) -> bool:
    segments = result.get("segments") or []
    if not segments:
        return True
    text = " ".join(segment["text"] for segment in segments)
    if len(text.strip()) < 20 and float(result.get("duration") or 0) > 60:
        return True
    return float(result.get("average_logprob", 0)) < config.GEMINI_FALLBACK_LOGPROB


def _clean_json_response(text: str) -> Any:
    cleaned = text.strip()
    cleaned = re.sub(r"^```(?:json)?\s*", "", cleaned, flags=re.IGNORECASE)
    cleaned = re.sub(r"\s*```$", "", cleaned)
    if not cleaned:
        raise ValueError("Gemini returned an empty response.")

    try:
        return json.loads(cleaned)
    except json.JSONDecodeError as first_error:
        # Some models still add a short explanation before/after the JSON even
        # when the prompt asks for JSON only. Extract one balanced object before
        # giving up, while respecting braces and escaped quotes inside strings.
        start = cleaned.find("{")
        if start < 0:
            raise ValueError("Gemini did not return a JSON object.") from first_error

        in_string = False
        escaped = False
        depth = 0
        for index in range(start, len(cleaned)):
            character = cleaned[index]
            if in_string:
                if escaped:
                    escaped = False
                elif character == "\\":
                    escaped = True
                elif character == '"':
                    in_string = False
                continue
            if character == '"':
                in_string = True
            elif character == "{":
                depth += 1
            elif character == "}":
                depth -= 1
                if depth == 0:
                    candidate = cleaned[start:index + 1]
                    try:
                        return json.loads(candidate)
                    except json.JSONDecodeError:
                        break

        raise ValueError(
            f"Gemini returned malformed transcript JSON near character {first_error.pos}."
        ) from first_error


def gemini_transcribe(path: Path, language: str, progress_callback: Any = None) -> dict[str, Any]:
    if not gemini_settings.is_configured():
        raise RuntimeError("Gemini fallback is not configured.")
    try:
        from google import genai
    except ImportError as exc:
        raise RuntimeError("The google-genai package is not installed.") from exc

    if progress_callback:
        progress_callback("transcribing", 0, 0.0, "Uploading audio to Gemini…")
    client = genai.Client(api_key=gemini_settings.get_gemini_api_key())
    selected_model = gemini_settings.get_gemini_model()
    uploaded = client.files.upload(file=str(path))
    if progress_callback:
        progress_callback("transcribing", 10, 0.0, "Gemini is transcribing the upload…")
    prompt = f"""
Transcribe this audio accurately. The expected language is {language if language != 'auto' else 'auto-detect, including mixed languages'}.
Return only valid JSON with this exact structure:
{{"language":"detected language code","segments":[{{"start":0.0,"end":4.2,"text":"exact spoken words"}}]}}
Use seconds for timestamps. Do not summarize, translate, or omit repeated speech.
Escape every double quote and backslash inside spoken text as required by JSON. Do not include markdown fences or commentary.
""".strip()
    try:
        from google.genai import types

        response = client.models.generate_content(
            model=selected_model,
            contents=[uploaded, prompt],
            config=types.GenerateContentConfig(
                response_mime_type="application/json",
                response_schema={
                    "type": "OBJECT",
                    "properties": {
                        "language": {"type": "STRING"},
                        "segments": {
                            "type": "ARRAY",
                            "items": {
                                "type": "OBJECT",
                                "properties": {
                                    "start": {"type": "NUMBER"},
                                    "end": {"type": "NUMBER"},
                                    "text": {"type": "STRING"},
                                },
                                "required": ["start", "end", "text"],
                            },
                        },
                    },
                    "required": ["language", "segments"],
                },
            ),
        )
        parsed = getattr(response, "parsed", None)
        if parsed is not None:
            payload = parsed.model_dump() if hasattr(parsed, "model_dump") else parsed
        else:
            payload = _clean_json_response(response.text or "")
    finally:
        try:
            client.files.delete(name=uploaded.name)
        except Exception:
            pass
    if not isinstance(payload, dict):
        raise RuntimeError("Gemini returned an invalid transcript payload.")
    raw_segments = payload.get("segments", [])
    if not isinstance(raw_segments, list):
        raise RuntimeError("Gemini returned transcript segments in an invalid format.")
    segments = [{
        "start": round(float(item.get("start", 0)), 3),
        "end": round(float(item.get("end", item.get("start", 0))), 3),
        "text": str(item.get("text", "")).strip(),
        "speaker": None,
    } for item in raw_segments if isinstance(item, dict) and str(item.get("text", "")).strip()]
    if not segments:
        raise RuntimeError("Gemini returned no usable transcript segments.")
    return {
        "segments": segments,
        "language": str(payload.get("language", language)),
        "duration": max(item["end"] for item in segments),
        "engine": f"gemini/{selected_model}",
    }


GROQ_API_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
GROQ_MAX_UPLOAD_BYTES = 20 * 1024 * 1024
GROQ_TARGET_CHUNK_SECONDS = 20 * 60
GROQ_CHUNK_OVERLAP_SECONDS = 2.0
GROQ_TIMESTAMP_TOLERANCE = 5.0
GROQ_MAX_ATTEMPTS = 4
_GROQ_RETRY_DELAYS = (2.0, 5.0, 15.0)

_GROQ_LANGUAGE_NAMES = {
    "english": "en", "chinese": "zh", "mandarin": "zh", "cantonese": "yue",
    "malay": "ms", "indonesian": "id", "japanese": "ja", "korean": "ko",
    "spanish": "es", "french": "fr", "german": "de", "portuguese": "pt",
    "russian": "ru", "arabic": "ar", "hindi": "hi", "thai": "th",
    "vietnamese": "vi", "tamil": "ta",
}


def _normalize_groq_language(raw: Any) -> str:
    key = str(raw or "").strip().lower()
    if not key or key == "und" or (" " not in key and len(key) <= 3):
        return key.split("-")[0] if key else "und"
    return _GROQ_LANGUAGE_NAMES.get(key.split(" ")[0].split("(")[0].strip(), key)


def plan_groq_chunks(duration: float | None, chunk_seconds: float = GROQ_TARGET_CHUNK_SECONDS) -> list[tuple[float, float]]:
    """Time windows with a small overlap so words are not cut at boundaries."""
    if not duration or duration <= chunk_seconds:
        return []
    windows: list[tuple[float, float]] = []
    start = 0.0
    while start < duration:
        end = min(start + chunk_seconds, duration)
        tail = duration - end
        if windows and 0 < tail < GROQ_CHUNK_OVERLAP_SECONDS * 2:
            prev_start, _ = windows.pop()
            windows.append((prev_start, duration))
            break
        windows.append((start, end))
        if end >= duration:
            break
        start = end - GROQ_CHUNK_OVERLAP_SECONDS
    return windows


def _groq_error_message(status: int, body: str) -> str:
    detail = body.strip()[:300]
    try:
        payload = json.loads(body)
        detail = str(payload.get("error", {}).get("message", detail))[:300]
    except (ValueError, AttributeError):
        pass
    if status in (401, 403):
        return "Groq rejected the API key. Check the key in Settings — it may be wrong, revoked, or out of credit."
    if status == 404:
        return "Groq could not find the model. Use whisper-large-v3-turbo or whisper-large-v3."
    if status == 413:
        return "The audio upload was too large for Groq. Try a shorter clip or a smaller file."
    if status == 422:
        return f"Groq could not process this audio ({detail or 'unsupported media'})."
    if status == 429:
        return "Groq is rate-limiting this key right now. Wait a minute and try again."
    if 500 <= status <= 599:
        return "Groq had a server problem. Try again later."
    return f"Groq transcription failed (HTTP {status}: {detail or 'unknown error'})."


def _post_groq_chunk(chunk_path: Path, model: str, language: str, api_key: str) -> dict[str, Any]:
    import httpx

    suffix = chunk_path.suffix.lower()
    mime = {
        ".mp3": "audio/mpeg", ".m4a": "audio/mp4", ".mp4": "audio/mp4",
        ".wav": "audio/wav", ".flac": "audio/flac", ".ogg": "audio/ogg",
        ".opus": "audio/opus", ".webm": "audio/webm",
    }.get(suffix, "application/octet-stream")
    data: dict[str, Any] = {
        "model": model,
        "response_format": "verbose_json",
        "timestamp_granularities[]": "segment",
    }
    if language and language != "auto":
        data["language"] = language.lower().split("-")[0]
    last_error: Exception | None = None
    with chunk_path.open("rb") as handle:
        content = handle.read()
    for attempt in range(GROQ_MAX_ATTEMPTS):
        check = None
        try:
            with httpx.Client(timeout=600.0) as client:
                response = client.post(
                    GROQ_API_URL,
                    headers={"Authorization": f"Bearer {api_key}"},
                    data=data,
                    files={"file": (chunk_path.name, content, mime)},
                )
            if response.status_code in (401, 403, 404, 413, 422):
                raise RuntimeError(_groq_error_message(response.status_code, response.text))
            if response.status_code == 429 or 500 <= response.status_code <= 599:
                retry_after = response.headers.get("retry-after")
                wait = _GROQ_RETRY_DELAYS[min(attempt, len(_GROQ_RETRY_DELAYS) - 1)]
                try:
                    if retry_after is not None:
                        wait = max(1.0, min(float(retry_after), 60.0))
                except ValueError:
                    pass
                last_error = RuntimeError(_groq_error_message(response.status_code, response.text))
                if attempt < GROQ_MAX_ATTEMPTS - 1:
                    time.sleep(wait)
                    continue
                raise last_error
            response.raise_for_status()
            parsed = response.json()
            if not isinstance(parsed, dict):
                raise RuntimeError("Groq returned an invalid transcript payload.")
            return parsed
        except RuntimeError:
            raise
        except Exception as exc:
            last_error = exc
            if attempt < GROQ_MAX_ATTEMPTS - 1:
                time.sleep(_GROQ_RETRY_DELAYS[min(attempt, len(_GROQ_RETRY_DELAYS) - 1)])
    raise RuntimeError(f"Could not reach Groq: {last_error}") from last_error


def validate_groq_segments(
    segments: list[dict[str, Any]],
    chunk_start: float,
    chunk_length: float,
    audio_duration: float | None,
) -> list[dict[str, Any]]:
    """Reject structurally invalid timestamps so a corrupt chunk is never silently kept."""
    for position, item in enumerate(segments):
        label = f"section starting at {format_duration(chunk_start)}, segment {position + 1}"
        start = float(item.get("start", 0))
        end = float(item.get("end", start))
        if start != start or end != end:  # NaN
            raise ValueError(f"Groq returned a non-numeric timestamp ({label}).")
        if start < 0:
            raise ValueError(f"Groq returned a negative timestamp ({label}).")
        if not end > start:
            raise ValueError(f"Groq returned an empty or reversed segment ({label}).")
        if start > chunk_length + GROQ_TIMESTAMP_TOLERANCE:
            raise ValueError(f"Groq returned a timestamp beyond the audio it was given ({label}).")
        if position and start + 0.001 < float(segments[position - 1].get("start", 0)):
            raise ValueError(f"Groq returned out-of-order segments ({label}).")
        if audio_duration and chunk_start + start > audio_duration + GROQ_TIMESTAMP_TOLERANCE:
            raise ValueError(f"Groq returned a timestamp beyond the audio duration ({label}).")
    return segments


def merge_groq_chunks(
    segments: list[dict[str, Any]],
    chunk_start: float,
    chunk_length: float,
    first: bool,
) -> list[dict[str, Any]]:
    """Drop the overlapped head of follow-up chunks, then shift onto the global timeline once."""
    overlap = 0.0 if first else GROQ_CHUNK_OVERLAP_SECONDS
    merged: list[dict[str, Any]] = []
    for item in segments:
        start = float(item["start"])
        end = float(item["end"])
        if start < overlap - 0.05 or start > chunk_length + GROQ_TIMESTAMP_TOLERANCE:
            continue
        merged.append({
            "start": round(chunk_start + min(start, chunk_length), 3),
            "end": round(chunk_start + min(end, chunk_length), 3),
            "text": str(item.get("text", "")).strip(),
            "speaker": None,
        })
    return merged


def prepare_groq_chunks(path: Path, directory: Path) -> list[tuple[Path, float]]:
    """Split audio into upload-friendly chunks; every file is verified under the byte budget."""
    duration = probe_duration(path)
    if duration is None or (duration <= GROQ_TARGET_CHUNK_SECONDS and path.stat().st_size <= GROQ_MAX_UPLOAD_BYTES):
        return [(path, 0.0)]
    windows = plan_groq_chunks(duration) or [(0.0, duration)]
    chunks: list[tuple[Path, float]] = []
    counter = 0

    def encode(window_start: float, window_end: float) -> None:
        nonlocal counter
        length = window_end - window_start
        attempts = [
            ["-c:a", "libmp3lame", "-b:a", "64k"],
            ["-c:a", "mp3", "-b:a", "64k"],
            ["-c:a", "aac", "-b:a", "64k", "-f", "mp4"],
        ]
        encoded = False
        target = directory / "groq-chunk-000.mp3"
        for codec_args in attempts:
            suffix = ".m4a" if "aac" in codec_args else ".mp3"
            counter += 1
            candidate = directory / f"groq-chunk-{counter:03d}{suffix}"
            result = subprocess.run(
                ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                 "-i", str(path), "-ss", f"{window_start:.3f}", "-t", f"{length:.3f}",
                 "-vn", "-ac", "1", "-ar", "16000", *codec_args, str(candidate)],
                capture_output=True, text=True, check=False,
            )
            if result.returncode == 0 and candidate.stat().st_size > 0:
                target = candidate
                encoded = True
                break
            candidate.unlink(missing_ok=True)
        if not encoded:
            raise RuntimeError("FFmpeg could not encode this audio for Groq upload.")
        if target.stat().st_size <= GROQ_MAX_UPLOAD_BYTES or length <= 15:
            chunks.append((target, window_start))
            return
        target.unlink(missing_ok=True)
        mid = window_start + length / 2
        encode(window_start, min(mid + GROQ_CHUNK_OVERLAP_SECONDS, window_end))
        encode(mid - GROQ_CHUNK_OVERLAP_SECONDS, window_end)

    for start, end in windows:
        encode(start, end)
    chunks.sort(key=lambda item: item[1])
    return chunks


def groq_transcribe(path: Path, language: str, model: str, progress_callback: Any = None) -> dict[str, Any]:
    if not groq_settings.is_configured():
        raise RuntimeError("Add your Groq API key in Settings to use Groq transcription.")
    selected_model = groq_settings.normalize_groq_model_id(model or groq_settings.get_groq_model())
    api_key = groq_settings.get_groq_api_key()
    work_dir = path.parent / "groq-chunks"
    work_dir.mkdir(parents=True, exist_ok=True)
    chunks = prepare_groq_chunks(path, work_dir)
    multi = len(chunks) > 1 or chunks[0][0] != path

    def report(position: int, detail: str) -> None:
        if progress_callback:
            processed = chunks[position][1] if multi else 0.0
            progress_callback("transcribing", round(position / len(chunks) * 100), processed, detail)

    merged: list[dict[str, Any]] = []
    detected_language = "und"
    logprobs: list[float] = []
    audio_duration = probe_duration(path)
    try:
        for position, (chunk_path, offset) in enumerate(chunks):
            report(position, f"Sending section {position + 1} of {len(chunks)} to Groq…")
            try:
                payload = _post_groq_chunk(chunk_path, selected_model, language, api_key)
            except Exception as exc:
                label = f"section {position + 1} ({format_duration(offset)})"
                raise RuntimeError(f"Groq transcription failed for {label}: {exc}") from exc
            raw_segments = payload.get("segments", [])
            if not isinstance(raw_segments, list):
                raise RuntimeError("Groq returned transcript segments in an invalid format.")
            cleaned = [{
                "start": round(float(item.get("start", 0)), 3),
                "end": round(float(item.get("end", item.get("start", 0))), 3),
                "text": str(item.get("text", "")).strip(),
            } for item in raw_segments if isinstance(item, dict) and str(item.get("text", "")).strip()]
            chunk_length = float(payload.get("duration") or 0) or None
            if chunk_length is None and cleaned:
                chunk_length = max(item["end"] for item in cleaned)
            validate_groq_segments(cleaned, offset, chunk_length or 0.0, audio_duration)
            if multi:
                merged.extend(merge_groq_chunk(cleaned, offset, chunk_length or 0.0, first=position == 0))
            else:
                merged.extend({
                    "start": item["start"], "end": item["end"], "text": item["text"], "speaker": None,
                } for item in cleaned)
                detected_language = _normalize_groq_language(payload.get("language", language))
            for item in raw_segments:
                if isinstance(item, dict) and isinstance(item.get("avg_logprob"), (int, float)):
                    logprobs.append(float(item["avg_logprob"]))
            if detected_language == "und":
                detected_language = _normalize_groq_language(payload.get("language", language))
            report(position + 1 if position + 1 < len(chunks) else position, f"Groq transcribed section {position + 1} of {len(chunks)}.")
    finally:
        for chunk_path, _ in chunks:
            if chunk_path != path:
                chunk_path.unlink(missing_ok=True)
    if not merged:
        raise RuntimeError("Groq returned no usable transcript segments.")
    average_logprob = sum(logprobs) / len(logprobs) if logprobs else -99.0
    return {
        "segments": merged,
        "language": detected_language,
        "duration": max(item["end"] for item in merged),
        "average_logprob": average_logprob,
        "engine": f"groq/{selected_model}",
    }


def _get_diarization_pipeline() -> Any:
    global _DIARIZATION_PIPELINE
    if not config.HF_TOKEN:
        raise RuntimeError("Speaker identification needs HF_TOKEN in backend/.env.")
    with _DIARIZATION_LOCK:
        if _DIARIZATION_PIPELINE is None:
            try:
                from pyannote.audio import Pipeline
            except ImportError as exc:
                raise RuntimeError("Install backend/requirements-diarization.txt to identify speakers.") from exc
            try:
                _DIARIZATION_PIPELINE = Pipeline.from_pretrained(config.DIARIZATION_MODEL, token=config.HF_TOKEN)
            except TypeError:
                _DIARIZATION_PIPELINE = Pipeline.from_pretrained(config.DIARIZATION_MODEL, use_auth_token=config.HF_TOKEN)
            try:
                import torch
                if torch.cuda.is_available():
                    _DIARIZATION_PIPELINE.to(torch.device("cuda"))
            except Exception:
                pass
    return _DIARIZATION_PIPELINE


def add_speakers(path: Path, segments: list[dict[str, Any]]) -> list[dict[str, Any]]:
    diarizer = _get_diarization_pipeline()
    output = diarizer(str(path))
    annotation = getattr(output, "speaker_diarization", output)
    turns: list[tuple[float, float, str]] = []
    for turn, _, label in annotation.itertracks(yield_label=True):
        turns.append((float(turn.start), float(turn.end), str(label)))
    label_map: dict[str, str] = {}
    next_number = 1
    for segment in segments:
        best_label = None
        best_overlap = 0.0
        for start, end, label in turns:
            overlap = max(0.0, min(float(segment["end"]), end) - max(float(segment["start"]), start))
            if overlap > best_overlap:
                best_overlap = overlap
                best_label = label
        if best_label is not None:
            if best_label not in label_map:
                label_map[best_label] = f"Speaker {next_number}"
                next_number += 1
            segment["speaker"] = label_map[best_label]
        else:
            segment["speaker"] = "Speaker 1"
    return segments


SUMMARY_STYLE_INSTRUCTIONS = {
    "brief": (
        "Create a concise summary for quick understanding. Give a short overview, then 3-6 key points. "
        "Prefer only the most important information and avoid repetition."
    ),
    "standard": (
        "Create a balanced summary with a clear overview, the main ideas, important supporting details, "
        "and a concise conclusion. Include timestamps for the most useful points."
    ),
    "detailed": (
        "Create a thorough summary that captures the main argument, supporting details, examples, explanations, "
        "important context, and conclusions. Use timestamps throughout when they help the reader return to the source."
    ),
    "study_notes": (
        "Turn the transcript into structured study notes. Emphasize key concepts, definitions, examples, important facts, "
        "relationships between ideas, and review-worthy takeaways."
    ),
    "key_points": (
        "Focus on the most important takeaways, decisions, recommendations, action items, conclusions, and memorable facts. "
        "Prefer concise bullets and useful timestamps."
    ),
    "custom": (
        "Follow the user's custom summary instructions while still obeying the accuracy, Markdown, and timestamp rules below."
    ),
}

SUMMARY_STYLE_LABELS = {
    "brief": "Brief",
    "standard": "Standard",
    "detailed": "Detailed",
    "study_notes": "Study Notes",
    "key_points": "Key Points",
    "custom": "Custom",
}


def normalize_summary_style(style: str) -> str:
    normalized = (style or "standard").strip().lower().replace("-", "_").replace(" ", "_")
    if normalized not in SUMMARY_STYLE_INSTRUCTIONS:
        raise ValueError(
            "Summary style must be brief, standard, detailed, study_notes, key_points, or custom."
        )
    return normalized


SUMMARY_LANGUAGE_NAMES = {
    "zh": "Simplified Chinese",
    "zh-cn": "Simplified Chinese",
    "zh-tw": "Traditional Chinese",
    "en": "English",
    "ms": "Malay",
    "ja": "Japanese",
    "ko": "Korean",
    "id": "Indonesian",
}


def normalize_summary_language(language: str) -> str:
    """Accept a locale code ("zh-CN") or a display name ("English"); the Gemini
    prompt needs the name, and unknown values pass through untouched."""
    value = (language or "").strip()
    if not value:
        return "Simplified Chinese"
    return SUMMARY_LANGUAGE_NAMES.get(value.lower(), value)


def normalize_summary_custom_instructions(value: str | None) -> str:
    instructions = (value or "").strip()
    if len(instructions) > 4000:
        raise ValueError("Custom summary instructions must be 4,000 characters or fewer.")
    return instructions


def make_summary(
    segments: list[dict[str, Any]],
    language: str,
    style: str,
    custom_instructions: str = "",
) -> str:
    if not gemini_settings.is_configured():
        raise RuntimeError("Add your Gemini API key in Gemini Settings to create summaries.")
    try:
        from google import genai
    except ImportError as exc:
        raise RuntimeError("The google-genai package is not installed.") from exc
    transcript = "\n".join(
        f"[{item['start']:.1f}s] {item.get('speaker') or ''}: {item['text']}" for item in segments
    )
    if len(transcript) > 350_000:
        transcript = transcript[:350_000]
    style_id = normalize_summary_style(style)
    custom_instructions = normalize_summary_custom_instructions(custom_instructions)
    if style_id == "custom" and not custom_instructions:
        raise ValueError("Custom summary style requires custom instructions.")

    style_instruction = SUMMARY_STYLE_INSTRUCTIONS[style_id]
    custom_block = (
        f"\nUSER CUSTOM INSTRUCTIONS:\n{custom_instructions}\n"
        if custom_instructions
        else ""
    )
    target_language_name = normalize_summary_language(language)
    simplified_script_block = ""
    if "simplified" in target_language_name.lower():
        # Whisper flips between Simplified and Traditional Chinese, and the model
        # happily mirrors a Traditional transcript unless told otherwise.
        simplified_script_block = (
            "\nThe transcript may be written in Traditional Chinese characters. "
            "Write your summary using Simplified Chinese characters only, even where the source uses Traditional ones.\n"
        )

    prompt = f"""
You are summarizing a timestamped transcript for personal study.
Write the summary in {target_language_name}.
{simplified_script_block}
SUMMARY STYLE: {SUMMARY_STYLE_LABELS[style_id]}
{style_instruction}
{custom_block}
Return clean Markdown only. Do not use Markdown code fences.
Use ## headings for major sections, blank lines between blocks, numbered or bullet lists where they improve readability, and **bold** for important terms.
Choose section headings that fit the selected style and source content.
Preserve technical English terms in parentheses after their Chinese term when useful.
Do not invent facts, claims, names, or details that are not supported by the transcript.
When citing an important point, include its nearest timestamp in the form [123.4s].
Avoid unnecessary repetition.

TRANSCRIPT:
{transcript}
""".strip()
    client = genai.Client(api_key=gemini_settings.get_gemini_api_key())
    response = client.models.generate_content(model=gemini_settings.get_gemini_model(), contents=prompt)
    summary = (response.text or "").strip()
    if not summary:
        raise RuntimeError("Gemini returned an empty summary.")
    return summary


def run_job(
    job_id: str, *, upload_path: str | None, source_url: str | None,
    model_name: str, language: str, diarize: bool,
    summary_language: str, summary_style: str,
    summary_custom_instructions: str = "",
    generate_summary: bool = False,
    allow_gemini_fallback: bool = False,
    cloud_provider: str = "local",
    groq_model: str = "",
) -> None:
    watchdog = StallWatchdog(job_id)
    watchdog.start()
    try:
        check_cancelled(job_id)
        storage.update_job(job_id, status="processing", progress=4, stage="acquire", error=None)
        with tempfile.TemporaryDirectory(prefix=f"sonicbrief-{job_id[:8]}-") as temp_dir_value:
            temp_dir = Path(temp_dir_value)
            check_cancelled(job_id)
            if source_url:
                def report_download(detail: str, percent: float) -> None:
                    storage.update_job(
                        job_id,
                        progress=4 + round((max(0.0, min(percent, 100.0)) / 100.0) * 14),
                        stage="acquire",
                        stage_detail=detail,
                        stage_progress=int(max(0.0, min(percent, 100.0))),
                    )

                media_path, metadata = download_audio(source_url, temp_dir, progress_callback=report_download)
                storage.update_job(
                    job_id,
                    title=metadata["title"],
                    creator_name=metadata.get("creator_name"),
                    duration=metadata.get("duration"),
                    progress=18,
                )
            elif upload_path:
                media_path = Path(upload_path)
            else:
                raise RuntimeError("No input media was provided.")

            check_cancelled(job_id)
            duration = probe_duration(media_path)
            if duration and duration > config.MAX_MEDIA_DURATION_SECONDS:
                raise RuntimeError(f"This audio is longer than the configured {config.MAX_MEDIA_DURATION_SECONDS // 3600}-hour limit.")
            if duration:
                storage.update_job(job_id, duration=duration)

            normalized_path = temp_dir / "normalized.wav"
            check_cancelled(job_id)
            normalize_audio(media_path, normalized_path)
            if cloud_provider == "groq":
                storage.update_job(job_id, progress=25, stage="transcribe", stage_detail="Preparing audio for Groq…", stage_progress=0, processed_duration=0)
            elif cloud_provider == "gemini":
                storage.update_job(job_id, progress=25, stage="transcribe", stage_detail="Uploading audio to Gemini…", stage_progress=0, processed_duration=0)
            else:
                storage.update_job(job_id, progress=25, stage="transcribe", stage_detail="Preparing Local Whisper…", stage_progress=0, processed_duration=0)

            def report_transcription(stage: str, stage_progress: int, processed: float, detail: str) -> None:
                overall = 25 + round((max(0, min(stage_progress, 100)) / 100) * 43)
                storage.update_job(
                    job_id,
                    progress=overall,
                    stage="transcribe",
                    stage_detail=detail,
                    stage_progress=stage_progress,
                    processed_duration=processed,
                )

            if cloud_provider in ("groq", "gemini"):
                # Cloud-only jobs fail honestly: there is no second provider to fall back to.
                check_cancelled(job_id)
                if cloud_provider == "groq":
                    result = groq_transcribe(normalized_path, language, groq_model, report_transcription)
                else:
                    result = gemini_transcribe(normalized_path, language, report_transcription)
            else:
                try:
                    check_cancelled(job_id)
                    result = local_transcribe(normalized_path, model_name, language, report_transcription)
                except Exception as local_error:
                    # A GPU that cannot actually execute (missing CUDA pack, driver reset)
                    # must degrade to the CPU instead of failing or freezing the job.
                    recovered_on_cpu = False
                    if cuda_math_libs_error() is None and looks_like_cuda_failure(local_error) and not _force_cpu_requested():
                        note_cuda_failure(str(local_error))
                        storage.add_warning(
                            job_id,
                            "GPU acceleration failed, so SonicBrief is transcribing on the CPU instead. "
                            "Install the CUDA pack for the GPU build to speed this up.",
                        )
                        check_cancelled(job_id)
                        result = local_transcribe(normalized_path, model_name, language, report_transcription)
                        recovered_on_cpu = True
                    if not recovered_on_cpu:
                        if not allow_gemini_fallback:
                            raise RuntimeError(f"Local transcription failed: {local_error}") from local_error
                        if not gemini_settings.is_configured():
                            raise RuntimeError(
                                f"Local transcription failed and optional Gemini fallback is not configured: {local_error}"
                            ) from local_error
                        storage.add_warning(job_id, f"Local transcription failed; Gemini fallback was used: {local_error}")
                        result = gemini_transcribe(normalized_path, language, report_transcription)
                else:
                    if transcript_needs_fallback(result):
                        if allow_gemini_fallback and gemini_settings.is_configured():
                            storage.add_warning(job_id, "Local transcription quality was low, so Gemini fallback was used.")
                            result = gemini_transcribe(normalized_path, language, report_transcription)
                        elif allow_gemini_fallback:
                            storage.add_warning(job_id, "Local transcription confidence was low; optional Gemini fallback is not configured.")
                        else:
                            storage.add_warning(job_id, "Local transcription confidence was low; cloud fallback was not requested.")

            check_cancelled(job_id)
            segments = result["segments"]
            storage.update_job(
                job_id, progress=68, stage="diarize", stage_detail="Preparing speaker identification…", stage_progress=0, processed_duration=result.get("duration") or duration,
                language=result.get("language"), engine=result.get("engine"), transcript_json=segments,
            )
            if not segments:
                # Music, silence or a speech-free clip: say so instead of finishing silently.
                storage.add_warning(job_id, "No speech was detected in this media, so the transcript is empty.")
            if diarize:
                try:
                    check_cancelled(job_id)
                    segments = add_speakers(normalized_path, segments)
                    storage.update_job(job_id, transcript_json=segments)
                except Exception as exc:
                    storage.add_warning(job_id, f"Speaker identification was skipped: {exc}")

            check_cancelled(job_id)
            if generate_summary and segments:
                storage.update_job(job_id, progress=84, stage="summarize")
                try:
                    summary = make_summary(segments, summary_language, summary_style, summary_custom_instructions)
                    check_cancelled(job_id)
                    storage.save_summary(job_id, summary, summary_language, summary_style, gemini_settings.get_gemini_model())
                except Exception as exc:
                    storage.add_warning(job_id, f"Summary was not generated: {exc}")
            else:
                if generate_summary:
                    storage.add_warning(job_id, "Summary was skipped because no speech was detected.")
                storage.update_job(
                    job_id,
                    progress=92,
                    stage="finalize",
                    stage_detail=("Transcript ready · no speech detected" if not segments else "Transcript ready · summary not requested"),
                    stage_progress=100,
                )

            storage.update_job(job_id, status="completed", progress=100, stage="complete")
    except JobCancelled:
        storage.update_job(job_id, status="cancelled", progress=84, stage="cancelled", error="Processing was cancelled by the user.")
    except Exception as exc:
        if is_cancelled(job_id):
            storage.update_job(job_id, status="cancelled", progress=84, stage="cancelled", error="Processing was cancelled by the user.")
        else:
            storage.update_job(job_id, status="failed", error=str(exc), progress=100)
    finally:
        watchdog.stop()
        clear_job(job_id)
        if upload_path:
            try:
                os.remove(upload_path)
            except OSError:
                pass
