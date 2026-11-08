"""Build the SonicBrief API server (PyInstaller onedir) for the Windows desktop app.

Output: build/desktop-app/server/SonicBrief-Server/  (SonicBrief-Server.exe + bundled
UI, FFmpeg, Whisper runtime, and, for ``--variant gpu``, the CUDA libraries).
The Tauri shell in desktop-app/ ships this folder as an app resource.

Release builds (CI) use the audited LGPL FFmpeg, exactly like package_windows_cpu.py.
``--dev-ffmpeg`` builds with whatever ffmpeg is on PATH and skips the licence
checks; the result is marked NOT-FOR-REDISTRIBUTION.txt and is only for local testing.

Run on 64-bit Windows with Python 3.11, backend requirements, PyInstaller, and a
built UI (npm run build:desktop-ui).
"""
from __future__ import annotations

import argparse
import importlib.metadata
import os
import shutil
import sys
import tempfile
from pathlib import Path

import PyInstaller.__main__

ROOT = Path(__file__).resolve().parents[1]
BACKEND = ROOT / "backend"
DEFAULT_OUT = ROOT / "build" / "desktop-app" / "server"
GPU_DISTRIBUTIONS = ("nvidia-cublas-cu12", "nvidia-cudnn-cu12")


def binary_path(filename: str) -> Path:
    configured = os.environ.get("FFMPEG_DIR", "")
    candidate = Path(configured) / filename if configured else Path(shutil.which(filename) or "")
    if not candidate.is_file():
        raise RuntimeError(f"{filename} not found. Set FFMPEG_DIR to the folder containing FFmpeg and FFprobe.")
    return candidate.resolve()


def gpu_dlls() -> list[Path]:
    """CUDA runtime DLLs from the nvidia-* wheels that CTranslate2 loads on Windows."""
    found: list[Path] = []
    for name in GPU_DISTRIBUTIONS:
        try:
            files = importlib.metadata.distribution(name).files or []
        except importlib.metadata.PackageNotFoundError as exc:
            raise RuntimeError(
                f"{name} is not installed. For --variant gpu run: pip install "
                '"nvidia-cublas-cu12>=12.4,<13" "nvidia-cudnn-cu12>=9,<10"'
            ) from exc
        found.extend(Path(str(f.locate())) for f in files if f.suffix.lower() == ".dll")
    if not found:
        raise RuntimeError("No CUDA DLLs found in the nvidia-* packages.")
    return found


def build(variant: str, dev_ffmpeg: bool, ui_dir: Path, out_dir: Path, bundle_cuda: bool = True) -> Path:
    if sys.platform != "win32" or sys.maxsize <= 2**32:
        raise RuntimeError("This build requires 64-bit Windows.")
    if sys.version_info[:2] != (3, 11):
        raise RuntimeError("Use Python 3.11 to build the desktop server.")
    if not (ui_dir / "index.html").is_file():
        raise RuntimeError(f"UI not built: {ui_dir}. Run `npm run build:desktop-ui` first.")

    ffmpeg, ffprobe = binary_path("ffmpeg.exe"), binary_path("ffprobe.exe")
    notices = ROOT / "third_party" / "btbn_static_licenses"
    ffmpeg_dlls: list[Path] = []
    license_data: list[str] = []
    if not dev_ffmpeg:
        ffmpeg_license = Path(os.environ.get("FFMPEG_LICENSE_FILE", ""))
        if not ffmpeg_license.is_file():
            raise RuntimeError("FFMPEG_LICENSE_FILE must point to the verified FFmpeg LGPL license.")
        from check_btbn_static_licenses import verify_license_bundle
        verify_license_bundle(notices)
        ffmpeg_dlls = sorted(ffmpeg.parent.glob("*.dll"))
        if not ffmpeg_dlls:
            raise RuntimeError("Expected separate FFmpeg LGPL shared DLLs, not an unreviewed static build.")
        license_data = [
            "--add-data", f"{ffmpeg_license.resolve()};third_party/ffmpeg",
            "--add-data", f"{notices};third_party/btbn_static_licenses",
        ]

    dlls = gpu_dlls() if variant == "gpu" else []
    # NSIS is a 32-bit program and cannot build installers above ~2 GiB, and the
    # CUDA runtime alone is ~1.7 GB, so the GPU variant ships without it by
    # default; users install the separate "CUDA pack" next to the app instead.
    build_dlls = dlls if bundle_cuda else []

    with tempfile.TemporaryDirectory(prefix="sonicbrief-desktop-package-") as stage:
        staged_backend = Path(stage) / "backend"
        staged_backend.mkdir()
        for source in BACKEND.glob("*.py"):  # Never stage backend/.env or user data.
            shutil.copy2(source, staged_backend / source.name)

        PyInstaller.__main__.run([
            str(ROOT / "sonicbrief_server.py"),
            "--name", "SonicBrief-Server",
            "--onedir",
            "--contents-directory", ".",
            "--noconfirm",
            "--clean",
            "--noupx",
            # Console subsystem keeps stdio valid; the desktop shell starts it with
            # CREATE_NO_WINDOW so no console window is ever shown.
            "--console",
            "--distpath", str(out_dir),
            "--workpath", str(ROOT / "build" / "pyinstaller-desktop"),
            "--specpath", str(ROOT / "build"),
            "--paths", str(BACKEND),
            "--add-data", f"{staged_backend};backend",
            "--add-data", f"{ui_dir};ui",
            # Ship the Chrome extension so the app can hand users a real folder
            # to "Load unpacked" instead of sending them to the repository.
            "--add-data", f"{ROOT / 'extension'};extension",
            "--add-binary", f"{ffmpeg};.",
            "--add-binary", f"{ffprobe};.",
            *[item for dll in ffmpeg_dlls for item in ("--add-binary", f"{dll};.")],
            *[item for dll in build_dlls for item in ("--add-binary", f"{dll};.")],
            *license_data,
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
            "--collect-submodules", "google.genai",
            "--collect-submodules", "uvicorn",
        ])

    bundle = out_dir / "SonicBrief-Server"
    executable = bundle / "SonicBrief-Server.exe"
    if not executable.is_file():
        raise RuntimeError(f"PyInstaller did not produce {executable}")

    if dev_ffmpeg:
        (bundle / "NOT-FOR-REDISTRIBUTION.txt").write_text(
            "Local test build: bundles an unaudited FFmpeg and skips the licence checks.\n"
            "Do not distribute. Release builds come from the desktop-app GitHub workflow.\n",
            encoding="utf-8",
        )
    else:
        os.environ["SONICBRIEF_PORTABLE_DIR"] = str(bundle)
        from collect_portable_licenses import main as collect_licenses
        collect_licenses()
    if variant == "gpu" and not bundle_cuda:
        # Leave the CUDA DLLs next to the build output so the pack script can zip them.
        pack_dir = out_dir.parent / "cuda-pack" / "cuda"
        if pack_dir.exists():
            shutil.rmtree(pack_dir)
        pack_dir.mkdir(parents=True)
        for dll in dlls:
            shutil.copy2(dll, pack_dir / dll.name)
        print(f"CUDA DLLs staged separately: {pack_dir} ({len(dlls)} files)")

    print(f"Desktop server built ({variant}): {executable}")
    return bundle


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--variant", choices=("cpu", "gpu"), default="cpu")
    parser.add_argument("--dev-ffmpeg", action="store_true", help="local test build: PATH ffmpeg, no licence checks")
    parser.add_argument(
        "--exclude-cuda",
        action="store_true",
        help="gpu variant only: leave the CUDA DLLs out of the bundle (they exceed the NSIS 2 GiB limit)",
    )
    parser.add_argument("--ui-dir", type=Path, default=ROOT / "build" / "desktop-ui")
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    args = parser.parse_args()
    build(
        args.variant,
        args.dev_ffmpeg,
        args.ui_dir.resolve(),
        args.out.resolve(),
        bundle_cuda=not args.exclude_cuda,
    )


if __name__ == "__main__":
    main()
