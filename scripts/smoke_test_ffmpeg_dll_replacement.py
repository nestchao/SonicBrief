"""Smoke-test replacing PyAV's seven hashed FFmpeg DLLs from a matching root build.

Copies the entire runtime to an isolated scratch directory, replaces only that
copy's private DLLs, then executes the replacement runtime's --self-test.
Does NOT establish that a user-modified independent FFmpeg rebuild is compatible.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

FAMILIES = frozenset((
    "avcodec", "avdevice", "avfilter", "avformat", "avutil", "swresample", "swscale"
))
HASHED = re.compile(
    r"^(avcodec|avdevice|avfilter|avformat|avutil|swresample|swscale)"
    r"-([0-9]+)-[0-9a-f]{32}\.dll$", re.IGNORECASE
)


def file_hash(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def dll_pairs(runtime: Path) -> list[tuple[Path, Path]]:
    root = runtime.resolve()
    private = root / "av.libs"
    if not private.is_dir() or not (root / "SonicBrief-MCP.exe").is_file():
        raise ValueError("Not a complete packaged Windows Portable runtime")
    pairs = []
    seen = set()
    for file in sorted(private.glob("*.dll")):
        m = HASHED.fullmatch(file.name)
        if not m:
            continue
        group, ver = m.group(1).lower(), m.group(2)
        if group in seen:
            raise ValueError("Duplicate PyAV private FFmpeg family: " + group)
        seen.add(group)
        original = root / f"{group}-{ver}.dll"
        if not original.is_file():
            raise ValueError("Missing matching root FFmpeg DLL: " + original.name)
        pairs.append((original, file))
    if seen != FAMILIES or len(pairs) != 7:
        raise ValueError("Expected exactly seven matched PyAV FFmpeg DLL families")
    return pairs


def substitute_private_dlls(runtime: Path) -> list[dict]:
    """Mutates ONLY the caller-supplied standalone runtime copy."""
    changes = []
    for source, target in dll_pairs(runtime):
        before = file_hash(target)
        source_digest = file_hash(source)
        shutil.copy2(source, target)
        after = file_hash(target)
        if after != source_digest:
            raise RuntimeError("DLL replacement verification failed: " + target.name)
        changes.append({
            "family": source.name.rsplit("-", 1)[0],
            "source_root_dll": source.name,
            "target_private_dll": target.relative_to(runtime).as_posix(),
            "original_private_sha256": before,
            "source_plain_sha256": source_digest,
            "substituted_private_sha256": after,
            "before_already_identical": before == source_digest,
        })
    return changes


def run(runtime: Path) -> dict:
    runtime = runtime.resolve()
    if not runtime.is_dir():
        raise ValueError("Packaged runtime is missing")
    original = [
        {"root_dll": src.name, "private_dll": dst.relative_to(runtime).as_posix(),
         "root_sha256": file_hash(src), "private_sha256": file_hash(dst)}
        for src, dst in dll_pairs(runtime)
    ]
    # Stage next to the original runtime so all I/O remains inside the worktree.
    with tempfile.TemporaryDirectory(prefix="dll-replace-", dir=runtime.parent) as temp:
        staging = Path(temp)
        replacement = staging / "runtime"
        shutil.copytree(runtime, replacement)
        changes = substitute_private_dlls(replacement)
        process_env = os.environ.copy()
        process_env["SONICBRIEF_FORCE_CPU"] = "1"
        process_env["SONICBRIEF_DATA_DIR"] = str(staging / "test-data")
        process = subprocess.run(
            [str((replacement / "SonicBrief-MCP.exe").resolve()), "--self-test"],
            cwd=staging, env=process_env, capture_output=True,
            text=True, encoding="utf-8", errors="replace",
            timeout=180, check=False,
        )
        if process.returncode:
            raise RuntimeError(
                "Isolated swapped-runtime self-test failed with exit "
                + str(process.returncode) + ": " + process.stderr[-1400:]
            )
        try:
            health = json.loads(process.stdout.strip().splitlines()[-1])
        except (ValueError, IndexError) as exc:
            raise RuntimeError("Swapped-runtime self-test did not return valid JSON") from exc
        if not (health.get("ok") and health.get("ffmpeg_available")
                and health.get("backend_runtime") == "native"):
            raise RuntimeError("Swapped-runtime self-test was not healthy")
    return {
        "schema": "sonicbrief-private-ffmpeg-dll-substitution-smoke-v1",
        "result": "PASS",
        "runtime_label": runtime.name,
        "original_pairs": original,
        "substitutions": changes,
        "self_test_exit_code": 0,
        "self_test_health": {
            "ok": bool(health["ok"]),
            "ffmpeg_available": bool(health["ffmpeg_available"]),
            "backend_runtime": str(health["backend_runtime"]),
        },
        "clearance": "NOT_CLEARED",
        "limitations": [
            "The replacement DLLs come from the SAME bundled FFmpeg build, "
            "not an independently modified/recompiled LGPL library.",
            "Running --self-test does not load/exercise all optional FFmpeg APIs; "
            "a separate one-host real Whisper CPU transcription E2E was performed.",
            "No proof of exact binary loader path for every DLL, user ability to "
            "rebuild custom versions, static LGPL relinkability, or full source notices.",
        ],
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runtime", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    results = run(args.runtime)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
    print("FFMPEG_DLL_REPLACEMENT_SMOKE_PASS", len(results["substitutions"]),
          "DLLs", "LEGAL_CLEARANCE=NOT_CLEARED")


if __name__ == "__main__":
    main()
