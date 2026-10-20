"""Build a self-contained Windows x64 CPU MCP from the existing Python project.

Run on Windows with Python 3.11, pip-installed backend requirements, PyInstaller,
and native FFmpeg/FFprobe executables. No local configuration/secrets are bundled.
"""
from __future__ import annotations

import os
import shutil
import sys
import tempfile
from pathlib import Path

import PyInstaller.__main__


ROOT = Path(__file__).resolve().parents[1]
BACKEND = ROOT / "backend"
DIST = ROOT / "build" / "portable"


def binary_path(filename: str) -> Path:
    configured = os.environ.get("FFMPEG_DIR", "")
    candidate = Path(configured) / filename if configured else Path(shutil.which(filename) or "")
    if not candidate.is_file():
        raise RuntimeError(f"{filename} not found. Set FFMPEG_DIR to the folder containing FFmpeg and FFprobe.")
    return candidate.resolve()


def build() -> None:
    if sys.platform != "win32" or sys.maxsize <= 2**32:
        raise RuntimeError("This release build requires 64-bit Windows.")
    if sys.version_info[:2] != (3, 11):
        raise RuntimeError("Use Python 3.11 to build the portable release.")

    ffmpeg = binary_path("ffmpeg.exe")
    ffprobe = binary_path("ffprobe.exe")
    ffmpeg_license_path = Path(os.environ.get("FFMPEG_LICENSE_FILE", ""))
    if not ffmpeg_license_path.is_file():
        raise RuntimeError("FFMPEG_LICENSE_FILE must point to the verified FFmpeg LGPL license.")
    from check_btbn_static_licenses import verify_license_bundle
    third_party_notices = ROOT / "third_party" / "btbn_static_licenses"
    verify_license_bundle(third_party_notices)
    dlls = sorted(ffmpeg.parent.glob("*.dll"))
    if not dlls:
        raise RuntimeError("Expected separate FFmpeg LGPL shared DLLs, not an unreviewed static build.")
    # Stage only backend source, never backend/.env or other user-generated files.
    with tempfile.TemporaryDirectory(prefix="sonicbrief-package-") as stage:
        staged_backend = Path(stage) / "backend"
        staged_backend.mkdir()
        for source in BACKEND.glob("*.py"):
            shutil.copy2(source, staged_backend / source.name)

        PyInstaller.__main__.run([
            str(ROOT / "sonicbrief_mcp.py"),
            "--name", "SonicBrief-MCP",
            "--onedir",
            "--contents-directory", ".",
            "--noconfirm",
            "--clean",
            "--noupx",
            "--console",
            "--distpath", str(DIST),
            "--workpath", str(ROOT / "build" / "pyinstaller"),
            "--specpath", str(ROOT / "build"),
            "--paths", str(BACKEND),
            "--add-data", f"{staged_backend};backend",
            "--add-binary", f"{ffmpeg};.",
            "--add-binary", f"{ffprobe};.",
            *[item for dll in dlls for item in ("--add-binary", f"{dll};.")],
            "--add-data", f"{ffmpeg_license_path.resolve()};third_party/ffmpeg",
            "--add-data", f"{third_party_notices};third_party/btbn_static_licenses",
            "--add-data", f"{ROOT / 'LICENSE'};.",
            "--add-data", f"{ROOT / 'THIRD_PARTY_NOTICES.md'};.",
            "--add-data", f"{ROOT / 'third_party' / 'python_licenses'};third_party/python_licenses",
            # Runtime plugin loaders don't always appear in static import analysis.
            "--hidden-import", "app",
            "--hidden-import", "config",
            "--hidden-import", "pipeline",
            "--hidden-import", "storage",
            "--hidden-import", "gemini_settings",
            "--collect-all", "faster_whisper",
            "--collect-all", "ctranslate2",
            "--collect-all", "av",
            "--collect-all", "onnxruntime",
            "--collect-all", "tokenizers",
            "--collect-all", "huggingface_hub",
            "--collect-all", "yt_dlp",
            "--collect-submodules", "mcp.server",
            "--collect-submodules", "mcp.shared",
            "--collect-submodules", "google.genai",
        ])

    executable = DIST / "SonicBrief-MCP" / "SonicBrief-MCP.exe"
    if not executable.is_file():
        raise RuntimeError(f"PyInstaller did not produce {executable}")
    from collect_portable_licenses import main as collect_licenses
    collect_licenses()
    print(f"Portable runtime built: {executable}")


if __name__ == "__main__":
    build()
