from __future__ import annotations

import os
import tempfile

import config

_API_KEY_ENV = "GEMINI_API_KEY"
_MODEL_ENV = "GEMINI_MODEL"
_LEGACY_MODEL_ENVS = ("GEMINI_TRANSCRIPTION_MODEL", "GEMINI_SUMMARY_MODEL")

DEFAULT_GEMINI_MODEL = "gemini-3.8-flash"
GEMINI_MODEL_OPTIONS = (
    {
        "id": "gemini-3.8-flash",
        "label": "Gemini 3.8 Flash",
        "description": "Recommended · latest balanced model",
    },
    {
        "id": "gemini-3.5-flash",
        "label": "Gemini 3.5 Flash",
        "description": "Balanced speed and quality",
    },
    {
        "id": "gemini-3.5-flash-lite",
        "label": "Gemini 3.5 Flash-Lite",
        "description": "Fastest and lowest-cost option",
    },
)
ALLOWED_GEMINI_MODELS = {item["id"] for item in GEMINI_MODEL_OPTIONS}


def get_gemini_api_key() -> str:
    """Return the effective Gemini key without exposing it through the API."""
    return os.getenv(_API_KEY_ENV, config.GEMINI_API_KEY).strip()


def get_gemini_model() -> str:
    """Return one supported Gemini model for both fallback and summaries."""
    candidates = (
        os.getenv(_MODEL_ENV, "").strip(),
        getattr(config, "GEMINI_MODEL", "").strip(),
        os.getenv("GEMINI_SUMMARY_MODEL", "").strip(),
        os.getenv("GEMINI_TRANSCRIPTION_MODEL", "").strip(),
    )
    for candidate in candidates:
        if candidate in ALLOWED_GEMINI_MODELS:
            return candidate
    return DEFAULT_GEMINI_MODEL


def list_gemini_models() -> list[dict[str, str]]:
    return [dict(item) for item in GEMINI_MODEL_OPTIONS]


def is_configured() -> bool:
    return bool(get_gemini_api_key())


def _read_env_lines() -> list[str]:
    path = config.BACKEND_DIR / ".env"
    if not path.exists():
        return []
    return path.read_text(encoding="utf-8").splitlines()


def _env_line_key(line: str) -> str | None:
    stripped = line.strip()
    if not stripped or stripped.startswith("#") or "=" not in stripped:
        return None
    return stripped.split("=", 1)[0].strip()


def _write_env_values(values: dict[str, str | None]) -> None:
    """Atomically update selected backend/.env values while preserving other settings."""
    path = config.BACKEND_DIR / ".env"
    lines = _read_env_lines()
    output: list[str] = []
    written: set[str] = set()

    for line in lines:
        key = _env_line_key(line)
        if key not in values:
            output.append(line)
            continue
        if key in written:
            continue
        written.add(key)
        value = values[key]
        if value is not None:
            output.append(f"{key}={value}")

    pending = [(key, value) for key, value in values.items() if key not in written and value is not None]
    if pending and output and output[-1].strip():
        output.append("")
    output.extend(f"{key}={value}" for key, value in pending)

    path.parent.mkdir(parents=True, exist_ok=True)
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


def save_gemini_api_key(api_key: str) -> None:
    """Persist the user's Gemini key in backend/.env without logging it."""
    key = api_key.strip()
    if not key:
        raise ValueError("API key cannot be empty.")
    if any(char in key for char in "\r\n"):
        raise ValueError("API key contains an invalid newline.")

    _write_env_values({_API_KEY_ENV: key})
    os.environ[_API_KEY_ENV] = key
    config.GEMINI_API_KEY = key


def save_gemini_model(model: str) -> str:
    """Persist one supported model and keep legacy per-task settings in sync."""
    selected = model.strip()
    if selected not in ALLOWED_GEMINI_MODELS:
        raise ValueError("Unsupported Gemini model.")

    values = {
        _MODEL_ENV: selected,
        "GEMINI_TRANSCRIPTION_MODEL": selected,
        "GEMINI_SUMMARY_MODEL": selected,
    }
    _write_env_values(values)

    os.environ[_MODEL_ENV] = selected
    os.environ["GEMINI_TRANSCRIPTION_MODEL"] = selected
    os.environ["GEMINI_SUMMARY_MODEL"] = selected
    config.GEMINI_MODEL = selected
    config.GEMINI_TRANSCRIPTION_MODEL = selected
    config.GEMINI_SUMMARY_MODEL = selected
    return selected


def clear_gemini_api_key() -> bool:
    """Remove only the Gemini key; keep the user's model preference."""
    existed = bool(get_gemini_api_key())
    _write_env_values({_API_KEY_ENV: None})
    os.environ.pop(_API_KEY_ENV, None)
    config.GEMINI_API_KEY = ""
    return existed
