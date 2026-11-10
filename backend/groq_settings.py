from __future__ import annotations

import os
import tempfile
import config

_API_KEY_ENV = "GROQ_API_KEY"
_MODEL_ENV = "GROQ_MODEL"

DEFAULT_GROQ_MODEL = "whisper-large-v3-turbo"
ALLOWED_GROQ_MODELS = ("whisper-large-v3-turbo", "whisper-large-v3")


def get_groq_api_key() -> str:
    """Return the effective Groq key without exposing it through the API."""
    return os.getenv(_API_KEY_ENV, config.GROQ_API_KEY).strip()


def normalize_groq_model_id(model: str) -> str:
    selected = model.strip()
    if selected not in ALLOWED_GROQ_MODELS:
        raise ValueError("Groq model must be whisper-large-v3-turbo or whisper-large-v3.")
    return selected


def get_groq_model() -> str:
    """Return the saved Groq model, preserving existing installations."""
    candidates = (
        os.getenv(_MODEL_ENV, "").strip(),
        getattr(config, "GROQ_MODEL", "").strip(),
        DEFAULT_GROQ_MODEL,
    )
    for candidate in candidates:
        if not candidate:
            continue
        try:
            return normalize_groq_model_id(candidate)
        except ValueError:
            continue
    return DEFAULT_GROQ_MODEL


def available_groq_models() -> list[dict[str, str]]:
    return [
        {
            "id": "whisper-large-v3-turbo",
            "label": "Whisper Large v3 Turbo",
            "description": "Faster, lower-cost transcription. Default Groq option.",
        },
        {
            "id": "whisper-large-v3",
            "label": "Whisper Large v3",
            "description": "Quality-oriented alternative for difficult audio.",
        },
    ]


def is_configured() -> bool:
    return bool(get_groq_api_key())


def _read_env_lines() -> list[str]:
    path = config.ENV_FILE
    if not path.exists():
        return []
    return path.read_text(encoding="utf-8").splitlines()


def _env_line_key(line: str) -> str | None:
    stripped = line.strip()
    if not stripped or stripped.startswith("#") or "=" not in stripped:
        return None
    return stripped.split("=", 1)[0].strip()


def _write_env_values(values: dict[str, str | None]) -> None:
    """Atomically update selected settings-file values while preserving other settings."""
    path = config.ENV_FILE
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


def save_groq_api_key(api_key: str) -> None:
    """Persist the user's Groq key in backend/.env without logging it."""
    key = api_key.strip()
    if not key:
        raise ValueError("API key cannot be empty.")
    if any(char in key for char in "\r\n"):
        raise ValueError("API key contains an invalid newline.")

    _write_env_values({_API_KEY_ENV: key})
    os.environ[_API_KEY_ENV] = key
    config.GROQ_API_KEY = key


def save_groq_model(model: str) -> str:
    """Persist the Groq model ID."""
    selected = normalize_groq_model_id(model)
    _write_env_values({_MODEL_ENV: selected})
    os.environ[_MODEL_ENV] = selected
    config.GROQ_MODEL = selected
    return selected


def clear_groq_api_key() -> bool:
    """Remove only the Groq key; keep the user's model preference."""
    existed = bool(get_groq_api_key())
    _write_env_values({_API_KEY_ENV: None})
    os.environ.pop(_API_KEY_ENV, None)
    config.GROQ_API_KEY = ""
    return existed
