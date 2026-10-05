from __future__ import annotations

import os
import re
import tempfile
from typing import Any

import config

_API_KEY_ENV = "GEMINI_API_KEY"
_MODEL_ENV = "GEMINI_MODEL"
_MODEL_ID_PATTERN = re.compile(r"^gemini-[a-z0-9][a-z0-9._-]{0,127}$", re.IGNORECASE)
_SPECIALIZED_MODEL_MARKERS = (
    "-tts",
    "-image",
    "-live",
    "computer-use",
    "robotics",
    "deep-research",
)

DEFAULT_GEMINI_MODEL = "gemini-3.8-flash"


def get_gemini_api_key() -> str:
    """Return the effective Gemini key without exposing it through the API."""
    return os.getenv(_API_KEY_ENV, config.GEMINI_API_KEY).strip()


def normalize_gemini_model_id(model: str) -> str:
    selected = model.strip()
    if selected.startswith("models/"):
        selected = selected[len("models/"):]
    if not _MODEL_ID_PATTERN.fullmatch(selected):
        raise ValueError("Invalid Gemini model ID.")
    return selected


def get_gemini_model() -> str:
    """Return the saved Gemini model, preserving existing installations."""
    candidates = (
        os.getenv(_MODEL_ENV, "").strip(),
        os.getenv("GEMINI_SUMMARY_MODEL", "").strip(),
        os.getenv("GEMINI_TRANSCRIPTION_MODEL", "").strip(),
        getattr(config, "GEMINI_MODEL", "").strip(),
        DEFAULT_GEMINI_MODEL,
    )
    for candidate in candidates:
        if not candidate:
            continue
        try:
            return normalize_gemini_model_id(candidate)
        except ValueError:
            continue
    return DEFAULT_GEMINI_MODEL


def _is_sonicbrief_model(model_id: str, supported_actions: set[str]) -> bool:
    if "generateContent" not in supported_actions:
        return False
    lowered = model_id.lower()
    if not lowered.startswith("gemini-"):
        return False
    return not any(marker in lowered for marker in _SPECIALIZED_MODEL_MARKERS)


def list_gemini_models(api_key: str | None = None) -> list[dict[str, str]]:
    """List generation-capable Gemini models available to one Google API key."""
    key = (api_key or get_gemini_api_key()).strip()
    if not key:
        raise ValueError("API key is required to load Gemini models.")

    try:
        from google import genai
    except ImportError as exc:
        raise RuntimeError("The google-genai package is not installed.") from exc

    client = genai.Client(api_key=key)
    options: dict[str, dict[str, str]] = {}

    for item in client.models.list():
        raw_name = str(getattr(item, "name", "") or "").strip()
        if not raw_name:
            continue

        try:
            model_id = normalize_gemini_model_id(raw_name)
        except ValueError:
            continue

        actions = {
            str(action)
            for action in (getattr(item, "supported_actions", None) or [])
            if action
        }
        if not _is_sonicbrief_model(model_id, actions):
            continue

        label = str(getattr(item, "display_name", "") or "").strip() or model_id
        description = str(getattr(item, "description", "") or "").strip()
        if not description:
            description = "Available to this API key · supports generateContent"

        options[model_id] = {
            "id": model_id,
            "label": label,
            "description": description,
        }

    def sort_key(option: dict[str, str]) -> tuple[int, str]:
        identifier = option["id"].lower()
        preview_rank = 1 if "preview" in identifier or "exp" in identifier else 0
        return (preview_rank, option["label"].lower())

    return sorted(options.values(), key=sort_key)


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
    """Persist a Gemini model ID and keep legacy per-task settings in sync."""
    selected = normalize_gemini_model_id(model)

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
