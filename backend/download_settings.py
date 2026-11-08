"""Where yt-dlp gets its cookies when YouTube asks to "confirm you're not a bot".

YouTube challenges anonymous downloads based on the caller's IP and session, so
some users never see it and others are blocked until they present a signed-in
cookie jar. No single browser works for everyone:

* Firefox stores cookies in a plain SQLite file that is readable while the
  browser is running, so it is the most reliable browser source.
* Chromium-family browsers (Chrome, Edge, Brave) encrypt cookies with Windows
  App-Bound Encryption, which yt-dlp frequently cannot decrypt, and their
  database is locked while the browser runs.
* An imported ``cookies.txt`` works regardless of which browser is installed.

So the default mode is ``auto``: try anonymously, then the candidate sources in
order, and remember which one worked. An explicit choice in Settings always wins,
including ``off``. ``YTDLP_BROWSER`` (used by agent-audio-workflow) is honoured
as a fallback so a browser configured once in backend/.env works in both places.
"""

from __future__ import annotations

import os
import shutil
from pathlib import Path

import config
import gemini_settings

_ENV_KEY = "SONICBRIEF_YTDLP_COOKIES_FROM_BROWSER"
_FALLBACK_ENV_KEY = "YTDLP_BROWSER"
_LAST_GOOD_ENV_KEY = "SONICBRIEF_YTDLP_LAST_GOOD_SOURCE"

# Preference order for `auto`. Firefox first, because its cookies are readable;
# the file next, because it needs no browser at all; Chromium last, because
# App-Bound Encryption makes it the least likely to work.
BROWSERS = ("firefox", "brave", "chromium", "chrome", "edge")

CHOICES = ("auto", "off", *BROWSERS, "file")

LABELS = {
    "auto": "Automatic (recommended)",
    "off": "Do not use cookies",
    "firefox": "Mozilla Firefox",
    "brave": "Brave",
    "chromium": "Chromium",
    "chrome": "Google Chrome",
    "edge": "Microsoft Edge",
    "file": "Imported cookies.txt",
}

BROWSER_NOTES = {
    "firefox": "Most reliable: cookies are readable even while Firefox is open.",
    "chrome": "Often fails on current Chrome: cookies are App-Bound encrypted.",
    "edge": "Often fails on current Edge: cookies are App-Bound encrypted.",
    "brave": "Chromium-based; may fail like Chrome.",
    "chromium": "Chromium-based; may fail like Chrome.",
}

MAX_COOKIE_BYTES = 1024 * 1024
_ALLOWED_COOKIE_DOMAINS = ("youtube.com", "google.com", "googlevideo.com", "youtube-nocookie.com")

_COOKIE_DIR = Path(config.DATA_DIR) / "cookies"
_COOKIE_FILE = _COOKIE_DIR / "youtube-cookies.txt"


# --------------------------------------------------------------------------- modes


def get_mode() -> str:
    explicit = (os.getenv(_ENV_KEY) or "").strip().lower()
    if explicit in CHOICES:
        return explicit
    fallback = (os.getenv(_FALLBACK_ENV_KEY) or "").strip().lower()
    if fallback in CHOICES:
        return fallback
    if fallback in BROWSERS:
        return fallback
    return "auto"


def save_mode(value: str) -> str:
    normalized = (value or "auto").strip().lower()
    if normalized not in CHOICES:
        raise ValueError(f"Unsupported cookie source: {value!r}. Choose one of {', '.join(CHOICES)}.")
    # Store explicitly so the choice overrides any YTDLP_BROWSER fallback.
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


# ----------------------------------------------------------------- browser detection


def _firefox_profile() -> bool:
    appdata = os.getenv("APPDATA")
    if not appdata:
        return False
    return (Path(appdata) / "Mozilla" / "Firefox" / "profiles.ini").is_file()


def _chromium_user_data() -> dict[str, Path]:
    local = os.getenv("LOCALAPPDATA")
    if not local:
        return {}
    base = Path(local)
    return {
        "brave": base / "BraveSoftware" / "Brave-Browser" / "User Data",
        "chromium": base / "Chromium" / "User Data",
        "chrome": base / "Google" / "Chrome" / "User Data",
        "edge": base / "Microsoft" / "Edge" / "User Data",
    }


def installed_browsers() -> list[str]:
    """Browsers that look installed, in preference order. Cheap path checks only."""
    found: list[str] = []
    if _firefox_profile():
        found.append("firefox")
    user_data = _chromium_user_data()
    for name in ("brave", "chromium", "chrome", "edge"):
        path = user_data.get(name)
        if path is not None and path.is_dir():
            found.append(name)
    return found


# --------------------------------------------------------------------- cookie file


def cookie_file_path() -> Path:
    return _COOKIE_FILE


def has_cookie_file() -> bool:
    return _COOKIE_FILE.is_file()


def delete_cookie_file() -> bool:
    try:
        _COOKIE_FILE.unlink()
        return True
    except FileNotFoundError:
        return False


def _cookie_rows(text: str) -> list[str]:
    """Keep only Netscape-format rows that belong to YouTube/Google."""
    rows: list[str] = []
    for raw in text.splitlines():
        line = raw.rstrip("\r")
        if not line.strip():
            continue
        http_only = line.startswith("#HttpOnly_")
        if line.startswith("#") and not http_only:
            continue
        body = line[len("#HttpOnly_"):] if http_only else line
        fields = body.split("\t")
        if len(fields) < 7:
            continue
        domain = fields[0].lstrip(".").lower()
        if not any(domain == allowed or domain.endswith("." + allowed) for allowed in _ALLOWED_COOKIE_DOMAINS):
            continue
        rows.append(("#HttpOnly_" if http_only else "") + body)
    return rows


def save_cookie_file(payload: bytes) -> int:
    """Validate and store an imported cookies.txt. Returns the number of kept rows."""
    if not payload:
        raise ValueError("The file is empty.")
    if len(payload) > MAX_COOKIE_BYTES:
        raise ValueError("That file is larger than 1 MB, which is not a cookies.txt export.")
    try:
        text = payload.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise ValueError("That file is not UTF-8 text, so it is probably not a cookies.txt export.") from exc
    if "\x00" in text:
        raise ValueError("That file looks binary, not like a cookies.txt export.")

    rows = _cookie_rows(text)
    if not rows:
        raise ValueError(
            "No YouTube or Google cookies were found. Export the cookies for youtube.com, not another site."
        )

    _COOKIE_DIR.mkdir(parents=True, exist_ok=True)
    header = "# Netscape HTTP Cookie File\n# Imported into SonicBrief; treat it like a password.\n\n"
    _COOKIE_FILE.write_text(header + "\n".join(rows) + "\n", encoding="utf-8")
    _restrict_permissions(_COOKIE_FILE)
    return len(rows)


def _restrict_permissions(path: Path) -> None:
    """Best effort: chmod is close to useless on Windows, so use icacls there."""
    try:
        if os.name == "nt":
            user = os.getenv("USERNAME")
            if user:
                os.system(
                    f'icacls "{path}" /inheritance:r /grant:r "{user}:F" >nul 2>&1'
                )
            return
        path.chmod(0o600)
    except OSError:
        pass


# -------------------------------------------------------------- source selection


def last_good_source() -> str | None:
    value = (os.getenv(_LAST_GOOD_ENV_KEY) or "").strip().lower()
    return value or None


def record_last_good(source: str | None) -> None:
    """Remember which source worked so the next job starts there."""
    normalized = (source or "").strip().lower() or None
    if normalized == last_good_source():
        return
    gemini_settings._write_env_values({_LAST_GOOD_ENV_KEY: normalized})
    if normalized:
        os.environ[_LAST_GOOD_ENV_KEY] = normalized
    else:
        os.environ.pop(_LAST_GOOD_ENV_KEY, None)


def source_chain() -> list[tuple[str, str]]:
    """Cookie sources to try after an anonymous attempt, in order.

    Each entry is ``("browser", name)`` or ``("file", str(path))``. An explicit
    browser is used alone (the user asked for it); everything else only includes
    sources that plausibly work on this machine.
    """
    mode = get_mode()
    if mode == "off":
        return []
    if mode == "file":
        return [("file", str(_COOKIE_FILE))] if has_cookie_file() else []
    if mode in BROWSERS:
        return [("browser", mode)]

    chain: list[tuple[str, str]] = []
    installed = installed_browsers()
    if "firefox" in installed:
        chain.append(("browser", "firefox"))
    if has_cookie_file():
        chain.append(("file", str(_COOKIE_FILE)))
    for name in ("brave", "chromium", "chrome", "edge"):
        if name in installed:
            chain.append(("browser", name))

    remembered = last_good_source()
    if remembered:
        match = [item for item in chain if item[0] == "browser" and item[1] == remembered]
        if not match and remembered == "file" and has_cookie_file():
            match = [("file", str(_COOKIE_FILE))]
        if match:
            chain = match + [item for item in chain if item != match[0]]
    return chain


def describe() -> dict[str, object]:
    try:
        import yt_dlp

        version = yt_dlp.version.__version__
    except Exception:
        version = None
    return {
        "mode": get_mode(),
        "label": LABELS[get_mode()],
        "choices": [
            {"value": choice, "label": LABELS[choice], "note": BROWSER_NOTES.get(choice, "")}
            for choice in CHOICES
        ],
        "installed_browsers": installed_browsers(),
        "cookie_file_present": has_cookie_file(),
        "cookie_file_path": str(_COOKIE_FILE),
        "last_good_source": last_good_source(),
        "yt_dlp_version": version,
    }


# Backwards-compatible helpers used by earlier code and tests.
def get_browser() -> str:
    return get_mode()


def save_browser(value: str) -> str:
    return save_mode(value)
