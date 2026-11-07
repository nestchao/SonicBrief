"""Entry point for the SonicBrief API server inside the desktop app.

The desktop shell starts this (frozen by PyInstaller as SonicBrief-Server.exe) and
shows http://127.0.0.1:7860/ in its window. The server runs the real FastAPI
backend and also serves the bundled web UI, so no Python install, Node, or
separate web server is needed.

It also runs from source for development:  python sonicbrief_server.py
"""
from __future__ import annotations

import multiprocessing
import os
import sys
from pathlib import Path

FROZEN = bool(getattr(sys, "frozen", False))
PROJECT_DIR = Path(sys.executable).resolve().parent if FROZEN else Path(__file__).resolve().parent
RUNTIME_DIR = Path(getattr(sys, "_MEIPASS", PROJECT_DIR))


def prepare_environment() -> None:
    """Point the backend at per-user folders and the bundled FFmpeg/CUDA DLLs."""
    if FROZEN:
        # FFmpeg, FFprobe and (GPU build) CUDA DLLs sit beside the executable.
        os.environ["PATH"] = os.pathsep.join((str(RUNTIME_DIR), os.environ.get("PATH", "")))
        # Same per-user folder as the portable MCP, so history/models are shared.
        local = Path(os.getenv("LOCALAPPDATA") or Path.home() / "AppData" / "Local") / "SonicBrief"
        os.environ.setdefault("SONICBRIEF_DATA_DIR", str(local / "data"))
        os.environ.setdefault("SONICBRIEF_ENV_FILE", str(local / "settings.env"))
        os.environ.setdefault("HF_HOME", str(local / "models"))
        os.environ.setdefault("XDG_CACHE_HOME", str(local / "cache"))
    else:
        sys.path.insert(0, str(PROJECT_DIR / "backend"))


def ensure_stdio() -> None:
    """Windowed/detached launches can leave stdout/stderr unset, which breaks uvicorn logging."""
    if sys.stdout is not None and sys.stderr is not None:
        return
    log_dir = Path(os.environ.get("SONICBRIEF_DATA_DIR") or PROJECT_DIR) / "logs"
    log_dir.mkdir(parents=True, exist_ok=True)
    stream = open(log_dir / "server.log", "a", encoding="utf-8", buffering=1)
    if sys.stdout is None:
        sys.stdout = stream
    if sys.stderr is None:
        sys.stderr = stream


def main() -> None:
    multiprocessing.freeze_support()
    prepare_environment()
    ensure_stdio()

    import uvicorn

    import config
    from app import app  # backend/app.py

    uvicorn.run(app, host=config.HOST, port=config.PORT, log_level="info")


if __name__ == "__main__":
    main()
