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
    try:
        return ctranslate2.get_cuda_device_count() > 0
    except Exception:
        return False


def ffmpeg_available() -> bool:
    return shutil.which("ffmpeg") is not None and shutil.which("ffprobe") is not None


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


def classify_url(url: str) -> str:
    parsed = urlparse(url.strip())
    if parsed.scheme not in {"http", "https"}:
        raise ValueError("The link must start with http:// or https://.")
    host = (parsed.hostname or "").lower()
    if host not in config.ALLOWED_URL_HOSTS:
        raise ValueError("Only YouTube and Bilibili links are supported.")
    return "bilibili" if "bilibili" in host or host == "b23.tv" else "youtube"


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
        raise RuntimeError(result.stderr.strip() or "FFmpeg could not decode this media file.")


def download_audio(url: str, directory: Path) -> tuple[Path, dict[str, Any]]:
    classify_url(url)
    output_template = str(directory / "source.%(ext)s")
    options = {
        "format": "bestaudio/best",
        "outtmpl": output_template,
        "noplaylist": True,
        "quiet": True,
        "no_warnings": True,
        "restrictfilenames": True,
        "postprocessors": [{"key": "FFmpegExtractAudio", "preferredcodec": "wav"}],
        "socket_timeout": 30,
    }
    try:
        with yt_dlp.YoutubeDL(options) as downloader:
            info = downloader.extract_info(url, download=True)
    except yt_dlp.utils.DownloadError as exc:
        raise RuntimeError(f"Media download failed: {exc}") from exc

    candidates = sorted(directory.glob("source.*"), key=lambda item: item.stat().st_mtime, reverse=True)
    if not candidates:
        raise RuntimeError("The downloader completed without producing an audio file.")
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


def local_transcribe(path: Path, model_name: str, language: str, progress_callback: Any = None) -> dict[str, Any]:
    if progress_callback:
        progress_callback("loading_model", 0, 0.0, f"Loading Whisper {model_name} model…")
    model, device, compute_type = get_model(model_name)
    if progress_callback:
        progress_callback("transcribing", 0, 0.0, f"Whisper is transcribing on {device.upper()} ({compute_type})…")
    segments_iter, info = model.transcribe(
        str(path),
        language=None if language == "auto" else language,
        beam_size=5,
        vad_filter=True,
        word_timestamps=True,
        condition_on_previous_text=True,
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


def make_summary(segments: list[dict[str, Any]], language: str, style: str) -> str:
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
    prompt = f"""
You are summarizing a timestamped transcript for personal study.
Write the summary in {'Simplified Chinese' if language == 'zh-CN' else language}.
Style: {style}. Target length: 1,000–1,500 Chinese characters when the content is long enough.
Return clean Markdown only. Do not use Markdown code fences.
Use ## headings for major sections, blank lines between blocks, numbered or bullet lists where they improve readability, and **bold** for important terms.
Use these sections when relevant: 核心概述、主要观点、重要细节、结论与行动项.
Preserve technical English terms in parentheses after their Chinese term.
Do not invent facts. When citing an important point, include its nearest timestamp in the form [123.4s].

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
) -> None:
    try:
        check_cancelled(job_id)
        storage.update_job(job_id, status="processing", progress=4, stage="acquire", error=None)
        with tempfile.TemporaryDirectory(prefix=f"sonicbrief-{job_id[:8]}-") as temp_dir_value:
            temp_dir = Path(temp_dir_value)
            check_cancelled(job_id)
            if source_url:
                media_path, metadata = download_audio(source_url, temp_dir)
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
                if not gemini_settings.is_configured():
                    raise RuntimeError(f"Local transcription failed and Gemini fallback is unavailable: {local_error}") from local_error
                storage.add_warning(job_id, f"Local transcription failed; Gemini fallback was used: {local_error}")
                result = gemini_transcribe(normalized_path, language)
            else:
                if transcript_needs_fallback(result):
                    if gemini_settings.is_configured():
                        storage.add_warning(job_id, "Local transcription quality was low, so Gemini fallback was used.")
                        result = gemini_transcribe(normalized_path, language)
                    else:
                        storage.add_warning(job_id, "Local transcription confidence was low; Gemini fallback is not configured.")

            check_cancelled(job_id)
            segments = result["segments"]
            storage.update_job(
                job_id, progress=68, stage="diarize", stage_detail="Preparing speaker identification…", stage_progress=0, processed_duration=result.get("duration") or duration,
                language=result.get("language"), engine=result.get("engine"), transcript_json=segments,
            )
            if diarize:
                try:
                    check_cancelled(job_id)
                    segments = add_speakers(normalized_path, segments)
                    storage.update_job(job_id, transcript_json=segments)
                except Exception as exc:
                    storage.add_warning(job_id, f"Speaker identification was skipped: {exc}")

            check_cancelled(job_id)
            storage.update_job(job_id, progress=84, stage="summarize")
            try:
                summary = make_summary(segments, summary_language, summary_style)
                check_cancelled(job_id)
                storage.save_summary(job_id, summary, summary_language, summary_style, gemini_settings.get_gemini_model())
            except Exception as exc:
                storage.add_warning(job_id, f"Summary was not generated: {exc}")

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
