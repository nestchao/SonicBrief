from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
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
import gemini_settings
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


def cuda_available() -> bool:
    # Portable CPU releases set this to avoid attempting CUDA without its DLLs.
    if os.getenv("SONICBRIEF_FORCE_CPU", "").strip().lower() in {"1", "true", "yes", "on"}:
        return False
    try:
        return ctranslate2.get_cuda_device_count() > 0
    except Exception:
        return False


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
    # Instagram, Facebook and Douyin frequently require a signed-in session.
    # Off by default; when enabled, yt-dlp reads the cookie store of the named
    # browser ("chrome", "edge", "firefox") for the target site only.
    cookies_browser = os.getenv("SONICBRIEF_YTDLP_COOKIES_FROM_BROWSER", "").strip().lower()
    if cookies_browser:
        options["cookiesfrombrowser"] = (cookies_browser,)
    if source_type == "bilibili":
        # Bilibili media is commonly served from CDN endpoints that can close a
        # long response early. Smaller ranged requests make recovery/resume more
        # reliable without changing which video/audio stream yt-dlp selects.
        options["http_chunk_size"] = 5 * 1024 * 1024

    last_error: Exception | None = None
    info: dict[str, Any] | None = None
    if progress_callback:
        progress_callback("Fetching media information…", 0.0)
    for attempt in range(2):
        try:
            with yt_dlp.YoutubeDL(options) as downloader:
                info = downloader.extract_info(url, download=True)
            break
        except yt_dlp.utils.DownloadError as exc:
            last_error = exc
            if attempt == 0:
                time.sleep(1.0)

    if info is None:
        raise RuntimeError(_download_error_message(last_error or RuntimeError("Unknown download error."))) from last_error

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
            capture_output=True, text=True, check=False,
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


def gemini_transcribe(path: Path, language: str) -> dict[str, Any]:
    if not gemini_settings.is_configured():
        raise RuntimeError("Gemini fallback is not configured.")
    try:
        from google import genai
    except ImportError as exc:
        raise RuntimeError("The google-genai package is not installed.") from exc

    client = genai.Client(api_key=gemini_settings.get_gemini_api_key())
    selected_model = gemini_settings.get_gemini_model()
    uploaded = client.files.upload(file=str(path))
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
) -> None:
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

            try:
                check_cancelled(job_id)
                result = local_transcribe(normalized_path, model_name, language, report_transcription)
            except Exception as local_error:
                if not allow_gemini_fallback:
                    raise RuntimeError(f"Local transcription failed: {local_error}") from local_error
                if not gemini_settings.is_configured():
                    raise RuntimeError(
                        f"Local transcription failed and optional Gemini fallback is not configured: {local_error}"
                    ) from local_error
                storage.add_warning(job_id, f"Local transcription failed; Gemini fallback was used: {local_error}")
                result = gemini_transcribe(normalized_path, language)
            else:
                if transcript_needs_fallback(result):
                    if allow_gemini_fallback and gemini_settings.is_configured():
                        storage.add_warning(job_id, "Local transcription quality was low, so Gemini fallback was used.")
                        result = gemini_transcribe(normalized_path, language)
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
        clear_job(job_id)
        if upload_path:
            try:
                os.remove(upload_path)
            except OSError:
                pass
