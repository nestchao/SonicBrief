"""Read PE import tables in an exact SonicBrief Windows Portable release ZIP.

Shows *declared native DLL imports* only, not every runtime LoadLibrary call,
statically linked object, copyright obligation, or legal release clearance.

Requires pefile==2024.8.26 (CI only; never bundled in SonicBrief runtime).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import zipfile
from collections import Counter, defaultdict
from pathlib import Path

NATIVE_SUFFIXES = (".dll", ".exe", ".pyd")
FFMPEG_DLL = re.compile(
    r"^(?:avcodec|avdevice|avfilter|avformat|avutil|swresample|swscale)"
    r"-[0-9]+(?:-[0-9a-f]{32})?\.dll$", re.IGNORECASE
)
PINNED_PEFILE_VERSION = "2024.8.26"


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def is_ffmpeg_dll(name: str) -> bool:
    return bool(FFMPEG_DLL.fullmatch(name.rsplit("/", 1)[-1].rsplit("\\", 1)[-1]))


def normalize_path(path: str) -> str:
    return path.replace("\\", "/").lower()


def native_binary_paths(names: list[str]) -> list[str]:
    return sorted(
        n for n in names
        if n.lower().endswith(NATIVE_SUFFIXES) and not n.endswith("/")
    )


def resolve_import(current_path: str, imported_name: str, index: dict[str, list[str]]) -> dict:
    """Provide likely local matches, not Windows loader proof (SxS/PATH may differ)."""
    basename = imported_name.replace("\\", "/").rsplit("/", 1)[-1].lower()
    files = index.get(basename, [])
    current_dir = normalize_path(current_path).rsplit("/", 1)[0] if "/" in current_path else ""
    same_dir = [file for file in files if normalize_path(file).rsplit("/", 1)[0] == current_dir]
    if len(same_dir) == 1:
        return {"kind": "same-directory-candidate", "path": same_dir[0]}
    root = [file for file in files if "/" not in file]
    if len(root) == 1:
        return {"kind": "release-root-candidate", "path": root[0]}
    if len(files) == 1:
        return {"kind": "unique-bundled-candidate", "path": files[0]}
    if files:
        return {"kind": "ambiguous-bundled-import", "paths": files}
    return {"kind": "not-in-bundle"}


def read_imports(data: bytes, filename: str) -> tuple[list[str], list[str]]:
    import pefile

    if pefile.__version__ != PINNED_PEFILE_VERSION:
        raise RuntimeError(
            "Install exactly pefile==" + PINNED_PEFILE_VERSION
            + " in a build-only environment."
        )
    if not data.startswith(b"MZ"):
        raise ValueError("Native file is not a Windows PE file: " + filename)
    pe = pefile.PE(data=data, fast_load=True)
    try:
        pe.parse_data_directories(directories=[
            pefile.DIRECTORY_ENTRY["IMAGE_DIRECTORY_ENTRY_IMPORT"],
            pefile.DIRECTORY_ENTRY["IMAGE_DIRECTORY_ENTRY_DELAY_IMPORT"],
        ])
        normal = sorted({
            imp.dll.decode("ascii", "replace").lower()
            for imp in getattr(pe, "DIRECTORY_ENTRY_IMPORT", [])
        })
        delayed = sorted({
            imp.dll.decode("ascii", "replace").lower()
            for imp in getattr(pe, "DIRECTORY_ENTRY_DELAY_IMPORT", [])
        })
        return normal, delayed
    finally:
        pe.close()


def inspect_archive(source: Path, expected_sha256: str | None = None) -> dict:
    if not source.is_file():
        raise FileNotFoundError(source)
    with source.open("rb") as handle:
        h = hashlib.sha256()
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            h.update(block)
    digest = h.hexdigest()
    if expected_sha256 and expected_sha256.lower() != digest:
        raise ValueError("Release ZIP does not match expected SHA-256")

    with zipfile.ZipFile(source) as archive:
        native = native_binary_paths(archive.namelist())
        if not native or "ffmpeg.exe" not in native or "ffprobe.exe" not in native:
            raise ValueError("Expected Windows Portable runtime binaries are missing")
        index: dict[str, list[str]] = defaultdict(list)
        for filename in native:
            index[filename.rsplit("/", 1)[-1].lower()].append(filename)
        native_imports = []
        import_edges = []
        for filename in native:
            data = archive.read(filename)
            regular, delay = read_imports(data, filename)
            native_imports.append({
                "file": filename, "size": len(data), "sha256": sha256(data),
                "regular_imports": regular, "delay_imports": delay
            })
            for library in set(regular) | set(delay):
                outcome = resolve_import(filename, library, index)
                import_edges.append({
                    "from": filename, "dll": library,
                    "import_mode": "delay-load" if library in delay else "normal",
                    "resolved_candidate": outcome
                })
    ffmpeg_edges = [edge for edge in import_edges if is_ffmpeg_dll(edge["dll"])]
    if not ffmpeg_edges:
        raise ValueError("No FFmpeg dynamic DLL imports found in Portable runtime")
    missing_ffmpeg = [edge for edge in ffmpeg_edges
                      if edge["resolved_candidate"]["kind"] == "not-in-bundle"]
    if missing_ffmpeg:
        raise ValueError("Unresolved FFmpeg imports in archive: " + json.dumps(missing_ffmpeg[:8]))
    top = [
        e for e in ffmpeg_edges
        if e["from"] in ("ffmpeg.exe", "ffprobe.exe")
        and e["resolved_candidate"].get("path", "").find("/") == -1
    ]
    pyav = [
        e for e in ffmpeg_edges
        if e["from"].startswith("av/") and e["from"].endswith(".pyd")
        and e["resolved_candidate"].get("path", "").startswith("av.libs/")
    ]
    if not top or not pyav:
        raise ValueError("Both root FFmpeg and hashed PyAV DLL links must be found")
    root_ffmpeg = sorted(
        n for n in native if "/" not in n and is_ffmpeg_dll(n)
    )
    private_ffmpeg = sorted(
        n for n in native if n.startswith("av.libs/") and is_ffmpeg_dll(n)
    )
    ffmpeg_group_edges = sorted(
        ffmpeg_edges, key=lambda e: (e["from"], e["dll"])
    )
    return {
        "schema": "sonicbrief-windows-pe-import-audit-v1",
        "source_zip": source.name, "source_zip_sha256": digest,
        "scanned_pe_file_count": len(native_imports),
        "native_file_sha256": native_imports,
        "summary": {
            "release_root_ffmpeg_dlls": len(root_ffmpeg),
            "pyav_private_ffmpeg_dlls": len(private_ffmpeg),
            "root_ffmpeg_files": root_ffmpeg,
            "pyav_private_ffmpeg_files": private_ffmpeg,
            "ffmpeg_cli_dynamic_import_edges": len(top),
            "pyav_extension_dynamic_import_edges": len(pyav),
            "ffmpeg_edges_without_bundled_name_match": len(missing_ffmpeg),
            "other_imports_not_found_in_bundle": sum(
                edge["resolved_candidate"]["kind"] == "not-in-bundle"
                for edge in import_edges if not is_ffmpeg_dll(edge["dll"])
            ),
            "import_resolution_limit": (
                "Paths are candidate matches from file names, not proof of Windows "
                "loader resolution or ability to substitute a modified DLL."
            )
        },
        "ffmpeg_dll_import_edges": ffmpeg_group_edges,
        "limitations": [
            "PE import tables show declared DLL dependencies; do not reveal "
            "statically linked code, runtime LoadLibrary, FFmpeg optional libraries, "
            "full actual Windows loader behavior or SBOM.",
            "BtbN FFmpeg CLI/root DLLs and PyAV hashed FFmpeg DLLs need "
            "separate corresponding-source and relinking analysis.",
            "LGPL and all external-library notices, source and patent obligations "
            "remain pending qualified review. NOT APPROVED FOR PUBLIC RELEASE.",
        ],
        "legal_clearance": "NOT_CLEARED"
    }


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--zip", required=True, type=Path)
    p.add_argument("--expected-sha256", default=None)
    p.add_argument("--output", required=True, type=Path)
    args = p.parse_args()
    report = inspect_archive(args.zip, args.expected_sha256)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    summary = report["summary"]
    print("PE_IMPORT_SCAN_PASS", report["scanned_pe_file_count"],
          "ROOT_FFMPEG_DLLS", summary["release_root_ffmpeg_dlls"],
          "PYAV_FFMPEG_DLLS", summary["pyav_private_ffmpeg_dlls"],
          "FFMPEG_DYNAMIC_EDGES", len(report["ffmpeg_dll_import_edges"]),
          "LEGAL_CLEARANCE=NOT_CLEARED")


if __name__ == "__main__":
    main()
