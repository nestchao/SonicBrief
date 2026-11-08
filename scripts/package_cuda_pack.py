"""Zip the CUDA runtime DLLs CTranslate2 needs into a downloadable "CUDA pack".

The GPU installer cannot carry them: the DLLs are ~1.7 GB and NSIS is a 32-bit
program that cannot build installers above ~2 GiB. The GPU build therefore ships
without them and users install this pack once.

Install it by unzipping the archive so that a ``cuda`` folder appears next to the
installed app (``%LOCALAPPDATA%\\SonicBrief\\cuda``); ``sonicbrief_server.py`` adds
that folder and a ``cuda`` folder beside the executable to ``PATH`` at startup.

Usage:
    .venv\\Scripts\\python.exe scripts\\package_cuda_pack.py
Output:
    build\\release\\SonicBrief-CudaPack.zip
"""
from __future__ import annotations

import argparse
import zipfile
from pathlib import Path

from package_windows_desktop import ROOT, gpu_dlls

READ_ME = """SonicBrief - CUDA runtime pack (NVIDIA GPUs)
================================================

SonicBrief's installer cannot include the CUDA runtime: these libraries are
about 1.7 GB and the installer format used (NSIS) cannot build installers above
2 GiB.

Without this pack SonicBrief still works - it transcribes on the CPU, which is
several times slower. With it, transcription runs on an NVIDIA GPU.

If the CUDA runtime is already installed on this PC (for example with a recent
NVIDIA driver), you may not need this pack at all.

How to install
--------------
1. Quit SonicBrief (tray icon -> Quit SonicBrief).
2. Unzip this archive so the "cuda" folder lands here:

       %LOCALAPPDATA%\\SonicBrief\\cuda

   The result should look like:

       %LOCALAPPDATA%\\SonicBrief\\cuda\\cublas64_12.dll
       %LOCALAPPDATA%\\SonicBrief\\cuda\\cudnn64_9.dll
       ...

3. Start SonicBrief. The status pill in the app should report GPU mode.

To remove the pack again, delete the %LOCALAPPDATA%\\SonicBrief\\cuda folder.

Licence
-------
The DLLs are NVIDIA CUDA redistributables, taken from the nvidia-cublas-cu12 and
nvidia-cudnn-cu12 wheels. They are redistributed under the NVIDIA CUDA Toolkit
End User License Agreement (https://docs.nvidia.com/cuda/eula/).
"""


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", type=Path, default=ROOT / "build" / "release" / "SonicBrief-CudaPack.zip")
    args = parser.parse_args()

    dlls = sorted(gpu_dlls(), key=lambda path: path.name.lower())
    if not dlls:
        raise SystemExit("No CUDA DLLs found. Install nvidia-cublas-cu12 and nvidia-cudnn-cu12 first.")

    args.out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.out, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=1) as archive:
        archive.writestr("README.txt", READ_ME)
        for dll in dlls:
            archive.write(dll, f"cuda/{dll.name}")

    size_mb = args.out.stat().st_size / (1024 * 1024)
    print(f"CUDA pack: {args.out} ({size_mb:.0f} MB, {len(dlls)} DLLs)")


if __name__ == "__main__":
    main()
