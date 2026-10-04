from __future__ import annotations

import os
import tempfile
from pathlib import Path

import config

_ENV_KEY = "GEMINI_API_KEY"


def get_gemini_api_key() -> str:
    """Return the effective Gemini key, preferring the local .env value."""
    return os.getenv(_ENV_KEY, config.GEMINI_API_KEY).strip()


def is_configured() -> bool:
    return bool(get_gemini_api_key())


def _read_env_lines() -> list[str]:
    path = config.BACKEND_DIR / ".env"
    if not path.exists():
        return []
    return path.read_text(encoding="utf-8").splitlines()


def save_gemini_api_key(api_key: str) -> None:
    """Persist the user's Gemini key in backend/.env without logging it."""
    key = api_key.strip()
    if not key:
        raise ValueError("API key cannot be empty.")
    if any(char in key for char in "\r\n"):
        raise ValueError("API key contains an invalid newline.")

    path = config.BACKEND_DIR / ".env"
    lines = _read_env_lines()
    replacement = f"{_ENV_KEY}={key}"
    replaced = False
    output: list[str] = []
    for line in lines:
        if line.lstrip().startswith(f"{_ENV_KEY}="):
            if not replaced:
                output.append(replacement)
                replaced = True
        else:
            output.append(line)
    if not replaced:
        if output and output[-1].strip():
            output.append("")
        output.append(replacement)

    path.parent.mkdir(parents=True, exist_ok=True)
    content = "\n".join(output).rstrip() + "\n"
    fd, temp_name = tempfile.mkstemp(prefix=".sonicbrief-env-", dir=str(path.parent), text=True)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(content)
        os.replace(temp_name, path)
    finally:
        try:
            os.unlink(temp_name)
        except FileNotFoundError:
            pass

    os.environ[_ENV_KEY] = key
    config.GEMINI_API_KEY = key


def clear_gemini_api_key() -> bool:
    """Remove the Gemini key from backend/.env and the current process."""
    path = config.BACKEND_DIR / ".env"
    lines = _read_env_lines()
    if not lines:
        os.environ.pop(_ENV_KEY, None)
        config.GEMINI_API_KEY = ""
        return False

    output = [line for line in lines if not line.lstrip().startswith(f"{_ENV_KEY}=")]
    content = "\n".join(output).rstrip()
    if content:
        content += "\n"

    fd, temp_name = tempfile.mkstemp(prefix=".sonicbrief-env-", dir=str(path.parent), text=True)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(content)
        os.replace(temp_name, path)
    finally:
        try:
            os.unlink(temp_name)
        except FileNotFoundError:
            pass

    os.environ.pop(_ENV_KEY, None)
    config.GEMINI_API_KEY = ""
    return True
