from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import sys
from pathlib import Path
from urllib.parse import urlparse

import yt_dlp
from dotenv import load_dotenv


ALLOWED_HOSTS = {
    "youtube.com",
    "www.youtube.com",
    "m.youtube.com",
    "youtu.be",
    "bilibili.com",
    "www.bilibili.com",
    "m.bilibili.com",
    "b23.tv",
}
SUPPORTED_FORMATS = {"mp3", "wav", "m4a"}
SUPPORTED_BROWSERS = {"brave", "chrome", "chromium", "edge", "firefox"}
SUPPORTED_JS_RUNTIMES = {"bun", "deno", "node", "quickjs"}
load_dotenv(Path(__file__).resolve().parent.parent / "backend" / ".env")


def explain_download_error(error: Exception) -> str:
    message = str(error)
    if "Sign in to confirm" in message or "not a bot" in message:
        message += (
            " YouTube requires a logged-in browser session for this request. "
            "Close the browser, sign in to YouTube, then retry with "
            "-Browser chrome or -Browser edge."
        )
    elif "Could not copy Chrome cookie database" in message:
        message += (
            " Close every Chrome/Edge window, including background processes, "
            "then retry with the matching -Browser option."
        )
    elif "Failed to decrypt with DPAPI" in message:
        message += (
            " Windows Chrome App-Bound Encryption prevented direct cookie reading. "
            "Try -Browser edge after fully closing Edge, or use Firefox."
        )
    return message


def validate_url(value: str) -> str:
    candidate = value.strip()
    markdown_match = re.fullmatch(r"\[[^\]]+\]\((https?://.+)\)", candidate)
    if markdown_match:
        candidate = markdown_match.group(1)

    parsed = urlparse(candidate)
    if parsed.scheme not in {"http", "https"}:
        raise ValueError("The link must start with http:// or https://.")
    if parsed.username or parsed.password:
        raise ValueError("Links with embedded credentials are not allowed.")
    host = (parsed.hostname or "").lower().rstrip(".")
    if host not in ALLOWED_HOSTS:
        raise ValueError("Only public YouTube and Bilibili links are supported.")
    return candidate


def progress_hook(data: dict[str, object]) -> None:
    status = data.get("status")
    if status == "downloading":
        percent = str(data.get("_percent_str", "")).strip()
        speed = str(data.get("_speed_str", "")).strip()
        eta = str(data.get("_eta_str", "")).strip()
        print(f"Downloading audio: {percent} at {speed}, ETA {eta}", file=sys.stderr, flush=True)
    elif status == "finished":
        print("Download finished; extracting audio with FFmpeg...", file=sys.stderr, flush=True)


def find_output(output_dir: Path, media_id: str, audio_format: str) -> Path:
    matches = sorted(output_dir.glob(f"{media_id}-*.{audio_format}"), key=lambda item: item.stat().st_mtime, reverse=True)
    if not matches:
        raise RuntimeError("The downloader finished without producing the expected audio file.")
    return matches[0]


def resolve_js_runtime(requested: str | None) -> tuple[str, str] | None:
    runtime = (requested or os.getenv("YTDLP_JS_RUNTIME", "auto")).strip().lower()
    if runtime in {"", "auto"}:
        runtime = "node" if shutil.which("node") else ""
    if not runtime:
        return None
    if runtime not in SUPPORTED_JS_RUNTIMES:
        raise ValueError(f"Unsupported JavaScript runtime: {runtime}")
    runtime_path = shutil.which(runtime)
    if not runtime_path:
        raise RuntimeError(
            f"JavaScript runtime '{runtime}' was not found in PATH. "
            "Install Node.js 22+ or pass --js-runtime auto."
        )
    return runtime, runtime_path


def collect_audio(
    url: str,
    output_dir: Path,
    audio_format: str,
    ffmpeg_location: str | None,
    browser: str | None = None,
    js_runtime: str | None = None,
) -> dict[str, object]:
    if audio_format not in SUPPORTED_FORMATS:
        raise ValueError(f"Unsupported audio format: {audio_format}")
    if not ffmpeg_location and shutil.which("ffmpeg") is None:
        raise RuntimeError("FFmpeg was not found. Install FFmpeg or pass --ffmpeg-location.")

    browser_name = (browser or os.getenv("YTDLP_BROWSER", "")).strip().lower()
    if browser_name and browser_name not in SUPPORTED_BROWSERS:
        raise ValueError(
            f"Unsupported browser: {browser_name}. Choose one of: "
            f"{', '.join(sorted(SUPPORTED_BROWSERS))}."
        )
    runtime_config = resolve_js_runtime(js_runtime)

    output_dir.mkdir(parents=True, exist_ok=True)
    options: dict[str, object] = {
        "format": "bestaudio/best",
        "outtmpl": str(output_dir / "%(id)s-%(title)s.%(ext)s"),
        "noplaylist": True,
        "restrictfilenames": True,
        "nooverwrites": True,
        "quiet": True,
        "no_warnings": False,
        "progress_hooks": [progress_hook],
        "postprocessors": [{
            "key": "FFmpegExtractAudio",
            "preferredcodec": audio_format,
            "preferredquality": "192",
        }],
    }
    if ffmpeg_location:
        options["ffmpeg_location"] = ffmpeg_location
    if browser_name:
        # Read the existing local browser cookie database; never export or save it in the project.
        options["cookiesfrombrowser"] = (browser_name,)
    if runtime_config:
        runtime_name, runtime_path = runtime_config
        options["js_runtimes"] = {runtime_name: {"path": runtime_path}}

    with yt_dlp.YoutubeDL(options) as downloader:
        info = downloader.extract_info(url, download=True)

    media_id = str(info.get("id") or "")
    if not media_id:
        raise RuntimeError("The source did not provide a stable media ID.")
    output_path = find_output(output_dir, media_id, audio_format)
    return {
        "ok": True,
        "title": info.get("title") or "Untitled audio",
        "id": media_id,
        "duration_seconds": info.get("duration"),
        "source_url": info.get("webpage_url") or url,
        "extractor": info.get("extractor_key") or info.get("extractor"),
        "format": audio_format,
        "path": str(output_path.resolve()),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Collect audio from a public YouTube or Bilibili URL.")
    parser.add_argument("--url", required=True)
    parser.add_argument("--audio-format", choices=sorted(SUPPORTED_FORMATS), default="mp3")
    parser.add_argument("--ffmpeg-location", default=None)
    parser.add_argument("--browser", choices=sorted(SUPPORTED_BROWSERS), default=None)
    parser.add_argument("--js-runtime", choices=sorted(SUPPORTED_JS_RUNTIMES | {"auto"}), default="auto")
    args = parser.parse_args()

    try:
        url = validate_url(args.url)
        result = collect_audio(
            url,
            Path(__file__).resolve().parent / "collected",
            args.audio_format,
            args.ffmpeg_location,
            args.browser,
            args.js_runtime,
        )
    except (ValueError, RuntimeError, yt_dlp.utils.DownloadError) as exc:
        print(json.dumps({"ok": False, "error": explain_download_error(exc)}, ensure_ascii=False), file=sys.stderr)
        return 1

    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
