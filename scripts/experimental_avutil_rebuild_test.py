"""Experimental independent LGPL libavutil replacement verification (Windows).

This script is NOT legal clearance. It tests a deliberately modified FFmpeg
library compiled from the pinned FFmpeg source in separate CI infrastructure,
compares needed ABI imports, then tests a copy of the Portable runtime only.
"""
from __future__ import annotations

import argparse
import ctypes
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path

MARKER = b"SONICBRIEF_INDEPENDENT_AVUTIL_REBUILD"
PINNED_PEFILE = "2024.8.26"


def digest(path: Path) -> str:
    with path.open("rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()


def pe_dlls(path: Path) -> tuple[set[str], dict[str, set[str]]]:
    import pefile

    if pefile.__version__ != PINNED_PEFILE:
        raise ValueError("Must use pinned pefile==" + PINNED_PEFILE)
    pe = pefile.PE(str(path))
    try:
        exported = getattr(pe, "DIRECTORY_ENTRY_EXPORT", None)
        exports = {
            item.name.decode("ascii", "replace")
            for item in (exported.symbols if exported else [])
            if item.name
        }
        imports = {
            lib.dll.decode("ascii", "replace").lower(): {
                sym.name.decode("ascii", "replace")
                for sym in lib.imports if sym.name
            }
            for lib in getattr(pe, "DIRECTORY_ENTRY_IMPORT", [])
        }
        return exports, imports
    finally:
        pe.close()


def required_imports_from_runtime(root: Path, target_name: str) -> dict[str, list[str]]:
    needed = {}
    for file in root.rglob("*"):
        if file.suffix.lower() not in {".exe", ".pyd", ".dll"}:
            continue
        _exports, imports = pe_dlls(file)
        matching = imports.get(target_name.lower(), set())
        if matching:
            needed[file.relative_to(root).as_posix()] = sorted(matching)
    return needed


def evaluate(root: Path, rebuilt: Path) -> dict:
    if not (root / "SonicBrief-MCP.exe").is_file():
        raise ValueError("Expected complete Portable MCP folder")
    private = list((root / "av.libs").glob("avutil-61-*.dll"))
    if len(private) != 1:
        raise ValueError("Expected exactly one private hashed avutil DLL")
    target = private[0]
    prior = digest(target)
    rebuilt_hash = digest(rebuilt)
    if prior == rebuilt_hash:
        raise ValueError("Rebuilt avutil is byte-identical to the original")
    if MARKER not in rebuilt.read_bytes():
        raise ValueError("Modified-source marker missing from rebuilt avutil")
    original_exports, _ = pe_dlls(target)
    new_exports, _ = pe_dlls(rebuilt)
    needed = required_imports_from_runtime(root, target.name)
    all_required = set().union(*[set(v) for v in needed.values()]) if needed else set()
    if not needed:
        raise ValueError("No DLL imports referencing private avutil were found")
    missing = sorted(all_required - new_exports)
    if missing:
        raise ValueError(f"Independently compiled avutil lacks {len(missing)} required API exports: {missing[:12]}")
    # Confirm against ALL original exports as separate evidence.
    original_missing = sorted(original_exports - new_exports)
    with tempfile.TemporaryDirectory(prefix="avutil-relink-", dir=root.parent) as temp:
        work = Path(temp)
        copied = work / "runtime"
        shutil.copytree(root, copied)
        candidate = copied / target.relative_to(root)
        shutil.copy2(rebuilt, candidate)
        if digest(candidate) != rebuilt_hash:
            raise ValueError("Replacement candidate hash mismatch")
        # Confirm that the code loaded is the modified source, rather than
        # merely a same-ABI original bundled DLL elsewhere on the search path.
        with os.add_dll_directory(str(copied / "av.libs")), os.add_dll_directory(str(copied)):
            lib = ctypes.WinDLL(str(candidate))
            info = lib.av_version_info
            info.restype = ctypes.c_char_p
            loaded_version = info()
            if loaded_version is None or MARKER not in loaded_version:
                raise ValueError("Modified av_version_info marker was not dynamically loaded")
            # Release DLL before running spawned EXE; Python remains test-only.
            del info
            del lib
        env = os.environ.copy()
        env["SONICBRIEF_FORCE_CPU"] = "1"
        env["SONICBRIEF_DATA_DIR"] = str(work / "data")
        proc = subprocess.run(
            [str(copied / "SonicBrief-MCP.exe"), "--self-test"],
            cwd=work, env=env, capture_output=True, text=True,
            encoding="utf-8", errors="replace", timeout=180,
        )
        if proc.returncode != 0:
            raise RuntimeError(f"Independent rebuilt avutil runtime self-test failed: {proc.stderr[-500:]}")
        try:
            health = json.loads(proc.stdout.strip().splitlines()[-1])
        except (ValueError, IndexError) as exc:
            raise RuntimeError("Runtime self-test did not return JSON") from exc
        if not health.get("ok") or not health.get("ffmpeg_available"):
            raise ValueError("Replaced runtime failed backend/FFmpeg health check")
    return {
        "schema": "sonicbrief-experimental-avutil-rebuild-v1",
        "status": "EXPERIMENTAL_PASS",
        "original_sha256": prior,
        "independent_modified_sha256": rebuilt_hash,
        "private_avutil_importing_modules": len(needed),
        "required_imported_exports_checked": len(all_required),
        "original_exports_missing_in_rebuild": len(original_missing),
        "modified_version_marker_loaded": True,
        "isolated_mcp_self_test": True,
        "legal_clearance": "NOT_CLEARED",
        "limitations": [
            "Only libavutil is independently recompiled; other six FFmpeg DLLs remain bundled.",
            "A minimally configured independent library may omit unused original exports; "
            "user compatibility beyond the tested runtime is not proven.",
            "No complete replacement/relink instruction, LGPL compliance approval, "
            "static third-party source/linkage audit, or actual Whisper model transcription.",
        ],
    }


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--runtime", required=True, type=Path)
    p.add_argument("--rebuilt-avutil", required=True, type=Path)
    p.add_argument("--output", required=True, type=Path)
    args = p.parse_args()
    report = evaluate(args.runtime.resolve(), args.rebuilt_avutil.resolve())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print("INDEPENDENT_AVUTIL_REBUILD_TEST_PASS", report["required_imported_exports_checked"],
          "required API exports", "LEGAL_CLEARANCE=NOT_CLEARED")


if __name__ == "__main__":
    main()
