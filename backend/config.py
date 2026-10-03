from __future__ import annotations

import os
from pathlib import Path

from dotenv import load_dotenv

BACKEND_DIR = Path(__file__).resolve().parent
PROJECT_DIR = BACKEND_DIR.parent
load_dotenv(BACKEND_DIR / ".env")

DATA_DIR = Path(os.getenv("SONICBRIEF_DATA_DIR", PROJECT_DIR / "data")).resolve()
UPLOAD_DIR = DATA_DIR / "uploads"
DATABASE_PATH = DATA_DIR / "sonicbrief.sqlite3"

HOST = os.getenv("SONICBRIEF_HOST", "127.0.0.1")
PORT = int(os.getenv("SONICBRIEF_PORT", "7860"))
MAX_UPLOAD_BYTES = int(os.getenv("MAX_UPLOAD_MB", "500")) * 1024 * 1024
# Allow substantially longer recordings by default. Keep this configurable
# through MAX_MEDIA_HOURS for machines that need a smaller limit.
MAX_MEDIA_HOURS = max(12, int(os.getenv("MAX_MEDIA_HOURS", "12")))
MAX_MEDIA_DURATION_SECONDS = MAX_MEDIA_HOURS * 60 * 60

GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "").strip()
GEMINI_TRANSCRIPTION_MODEL = os.getenv("GEMINI_TRANSCRIPTION_MODEL", "gemini-3.5-flash-lite").strip()
GEMINI_SUMMARY_MODEL = os.getenv("GEMINI_SUMMARY_MODEL", "gemini-3.5-flash-lite").strip()
GEMINI_FALLBACK_LOGPROB = float(os.getenv("GEMINI_FALLBACK_LOGPROB", "-1.2"))

HF_TOKEN = os.getenv("HF_TOKEN", "").strip()
DIARIZATION_MODEL = os.getenv("DIARIZATION_MODEL", "pyannote/speaker-diarization-3.1").strip()

ALLOWED_MODELS = {
    "turbo": "Whisper Turbo",
    "large-v3": "Whisper Large v3",
    "distil-large-v3": "Distil-Whisper Large v3",
    "small": "Whisper Small",
}
ALLOWED_EXTENSIONS = {
    ".mp3", ".wav", ".m4a", ".aac", ".flac", ".ogg", ".opus",
}
ALLOWED_URL_HOSTS = {
    "youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be",
    "bilibili.com", "www.bilibili.com", "m.bilibili.com", "b23.tv",
}


def ensure_directories() -> None:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    UPLOAD_DIR.mkdir(parents=True, exist_ok=True)
