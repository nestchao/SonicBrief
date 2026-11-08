"""Which browser yt-dlp should read cookies from.

YouTube periodically asks anonymous downloads to "confirm you're not a bot".
The only reliable answer is to present the cookies of a browser that is signed
in, so the download looks like ordinary browsing. yt-dlp reads those cookies
itself; all this module does is record the choice and keep os.environ in sync so
a change applies to the next job without restarting the app.

``YTDLP_BROWSER`` (used by agent-audio-workflow) is honoured as a fallback so a
browser configured once in backend/.env works in both places. An explicit
choice made in Settings always wins, including "off".
"""

from __future__ import annotations

import os
import shutil

import gemini_settings

_ENV_KEY = "SONICBRIEF_YTDLP_COOKIES_FROM_BROWSER"
_FALLBACK_ENV_KEY = "YTDLP_BROWSER"
CHOICES = ("off", "chrome", "edge", "firefox", "brave", "chromium")

LABELS = {
    "off": "Do not use browser cookies",
    "chrome": "Google Chrome",
    "edge": "Microsoft Edge",
    "firefox": "Mozilla Firefox",
    "brave": "Brave",
    "chromium": "Chromium",
}


def get_browser() -> str:
    explicit = (os.getenv(_ENV_KEY) or "").strip().lower()
    if explicit in CHOICES:
        return explicit
    fallback = (os.getenv(_FALLBACK_ENV_KEY) or "").strip().lower()
    return fallback if fallback in CHOICES else "off"


def save_browser(value: str) -> str:
    normalized = (value or "off").strip().lower()
    if normalized not in CHOICES:
        raise ValueError(f"Unsupported browser: {value!r}. Choose one of {', '.join(CHOICES)}.")
    # "off" is stored explicitly so it overrides the YTDLP_BROWSER fallback.
    gemini_settings._write_env_values({_ENV_KEY: normalized})
    os.environ[_ENV_KEY] = normalized
    return normalized


def js_runtimes() -> dict[str, dict[str, str]]:
    """JavaScript runtime yt-dlp needs to solve YouTube's challenges.

    yt-dlp only auto-detects Deno; Node is very common, so enable whichever
    is installed (Deno preferred) and pass its path explicitly.
    """
    for name in ("deno", "node"):
        path = shutil.which(name)
        if path:
            return {name: {"path": path}}
    return {}


def describe() -> dict[str, object]:
    current = get_browser()
    return {
        "cookies_browser": current,
        "label": LABELS[current],
        "choices": [{"value": choice, "label": LABELS[choice]} for choice in CHOICES],
    }
