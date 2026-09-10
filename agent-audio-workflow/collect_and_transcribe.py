from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path
from typing import Any

from dotenv import load_dotenv
import yt_dlp

from collect_audio import (
    SUPPORTED_BROWSERS,
    SUPPORTED_FORMATS,
    SUPPORTED_JS_RUNTIMES,
    collect_audio,
    explain_download_error,
    validate_url,
)


WORKFLOW_DIR = Path(__file__).resolve().parent
PROJECT_DIR = WORKFLOW_DIR.parent
TRANSCRIPT_DIR = WORKFLOW_DIR / "transcripts"
SUMMARY_DIR = WORKFLOW_DIR / "summaries"
load_dotenv(PROJECT_DIR / "backend" / ".env")


def clean_json_response(text: str) -> Any:
    cleaned = re.sub(r"^```(?:json)?\s*|\s*```$", "", text.strip(), flags=re.IGNORECASE)
    if not cleaned:
        raise RuntimeError("Gemini returned an empty transcription response.")
    try:
        return json.loads(cleaned)
    except json.JSONDecodeError as exc:
        raise RuntimeError("Gemini returned malformed transcript JSON.") from exc


def transcribe_with_gemini(audio_path: Path, language: str, model: str) -> dict[str, Any]:
    api_key = os.getenv("GEMINI_API_KEY", "").strip()
    if not api_key:
        raise RuntimeError("Add GEMINI_API_KEY to backend/.env before using Gemini transcription.")

    try:
        from google import genai
        from google.genai import types
    except ImportError as exc:
        raise RuntimeError("The google-genai package is not installed in the project venv.") from exc

    print(f"Uploading audio to Gemini for transcription with {model}...", file=sys.stderr, flush=True)
    client = genai.Client(api_key=api_key)
    uploaded = client.files.upload(file=str(audio_path))
    prompt = f"""
Transcribe this audio accurately. The expected language is {language if language != "auto" else "auto-detect, including mixed languages"}.
Return only valid JSON with this exact structure:
{{"language":"detected language code","segments":[{{"start":0.0,"end":4.2,"text":"exact spoken words"}}]}}
Use seconds for timestamps. Do not summarize, translate, or omit repeated speech.
Timestamps are best-effort estimates. Escape every double quote and backslash inside spoken text as valid JSON.
""".strip()

    try:
        response = client.models.generate_content(
            model=model,
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
        payload = parsed.model_dump() if hasattr(parsed, "model_dump") else parsed
        if payload is None:
            payload = clean_json_response(response.text or "")
    finally:
        try:
            client.files.delete(name=uploaded.name)
        except Exception:
            pass

    if not isinstance(payload, dict) or not isinstance(payload.get("segments"), list):
        raise RuntimeError("Gemini returned an invalid transcript structure.")

    segments = []
    for item in payload["segments"]:
        if not isinstance(item, dict):
            continue
        text = str(item.get("text", "")).strip()
        if not text:
            continue
        start = round(float(item.get("start", 0)), 3)
        end = round(float(item.get("end", start)), 3)
        segments.append({"start": start, "end": max(start, end), "text": text})
    if not segments:
        raise RuntimeError("Gemini returned no usable transcript segments.")

    return {
        "language": str(payload.get("language") or language),
        "segments": segments,
        "engine": f"gemini/{model}",
        "timestamps": "best-effort model estimates",
    }


def format_timestamp(seconds: float) -> str:
    total = max(0, int(seconds))
    hours, remainder = divmod(total, 3600)
    minutes, secs = divmod(remainder, 60)
    return f"{hours:02}:{minutes:02}:{secs:02}"


def save_transcript(audio_path: Path, audio: dict[str, object], transcript: dict[str, Any]) -> dict[str, str]:
    TRANSCRIPT_DIR.mkdir(parents=True, exist_ok=True)
    json_path = TRANSCRIPT_DIR / f"{audio_path.stem}.transcript.json"
    text_path = TRANSCRIPT_DIR / f"{audio_path.stem}.transcript.txt"
    payload = {
        "source": audio,
        "transcript": transcript,
    }
    json_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    text_path.write_text(
        "\n".join(
            f"[{format_timestamp(segment['start'])}] {segment['text']}"
            for segment in transcript["segments"]
        ) + "\n",
        encoding="utf-8",
    )
    return {"json_path": str(json_path.resolve()), "text_path": str(text_path.resolve())}


def summarize_with_gemini(transcript: dict[str, Any], model: str) -> str:
    api_key = os.getenv("GEMINI_API_KEY", "").strip()
    if not api_key:
        raise RuntimeError("Add GEMINI_API_KEY to backend/.env before generating a summary.")
    try:
        from google import genai
    except ImportError as exc:
        raise RuntimeError("The google-genai package is not installed in the project venv.") from exc

    source_text = "\n".join(
        f"[{segment['start']:.1f}s] {segment['text']}" for segment in transcript["segments"]
    )
    prompt = f"""
Summarize this timestamped transcript in Simplified Chinese.
Use Markdown headings where helpful. Include: 核心概述、主要观点、重要细节、结论与行动项.
Preserve technical English terms in parentheses after their Chinese term. Do not invent facts.

TRANSCRIPT:
{source_text[:350_000]}
""".strip()
    print(f"Generating summary with {model}...", file=sys.stderr, flush=True)
    client = genai.Client(api_key=api_key)
    summary = (client.models.generate_content(model=model, contents=prompt).text or "").strip()
    if not summary:
        raise RuntimeError("Gemini returned an empty summary.")
    return summary


def main() -> int:
    parser = argparse.ArgumentParser(description="Collect audio, transcribe it with Gemini, and optionally create a summary.")
    parser.add_argument("--url", required=True)
    parser.add_argument("--audio-format", choices=sorted(SUPPORTED_FORMATS), default="mp3")
    parser.add_argument("--language", default="auto")
    parser.add_argument("--summarize", action="store_true")
    parser.add_argument("--ffmpeg-location", default=None)
    parser.add_argument("--browser", choices=sorted(SUPPORTED_BROWSERS), default=None)
    parser.add_argument("--js-runtime", choices=sorted(SUPPORTED_JS_RUNTIMES | {"auto"}), default="auto")
    args = parser.parse_args()

    try:
        url = validate_url(args.url)
        audio = collect_audio(
            url,
            WORKFLOW_DIR / "collected",
            args.audio_format,
            args.ffmpeg_location,
            args.browser,
            args.js_runtime,
        )
        audio_path = Path(str(audio["path"]))
        transcription_model = os.getenv("GEMINI_TRANSCRIPTION_MODEL", "gemini-3.5-flash-lite").strip()
        transcript = transcribe_with_gemini(audio_path, args.language, transcription_model)
        transcript_paths = save_transcript(audio_path, audio, transcript)

        result: dict[str, object] = {
            "ok": True,
            "audio": audio,
            "transcript": {**transcript, **transcript_paths},
        }
        if args.summarize:
            summary_model = os.getenv("GEMINI_SUMMARY_MODEL", "gemini-3.5-flash-lite").strip()
            summary = summarize_with_gemini(transcript, summary_model)
            SUMMARY_DIR.mkdir(parents=True, exist_ok=True)
            summary_path = SUMMARY_DIR / f"{audio_path.stem}.summary.md"
            summary_path.write_text(summary + "\n", encoding="utf-8")
            result["summary"] = {"engine": f"gemini/{summary_model}", "path": str(summary_path.resolve())}
    except (ValueError, RuntimeError, yt_dlp.utils.DownloadError) as exc:
        print(json.dumps({"ok": False, "error": explain_download_error(exc)}, ensure_ascii=False), file=sys.stderr)
        return 1

    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
