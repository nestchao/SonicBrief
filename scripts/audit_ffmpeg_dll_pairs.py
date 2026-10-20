"""Compare the 7 release-root FFmpeg DLLs with hashed PyAV DLL copies.

A matching .text section is evidence of identical executable code bytes in a
particular PE section, not definitive proof of the full upstream build,
replaceability, static components, or legal compliance.

Dependency pefile==2024.8.26 is needed only at build/audit time.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import zipfile
from pathlib import Path


HASHED = re.compile(
    r"^(avcodec|avdevice|avfilter|avformat|avutil|swresample|swscale)"
    r"-([0-9]+)-([0-9a-f]{32})\.dll$", flags=re.IGNORECASE
)
ROOT = re.compile(
    r"^(avcodec|avdevice|avfilter|avformat|avutil|swresample|swscale)"
    r"-([0-9]+)\.dll$", flags=re.IGNORECASE
)
EXPECTED_FAMILIES = frozenset(
    ("avcodec", "avdevice", "avfilter", "avformat", "avutil", "swresample", "swscale")
)
PINNED_PEFILE_VERSION = "2024.8.26"


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def paired_names(names: set[str]) -> list[tuple[str, str]]:
    pairs = []
    seen = set()
    for path in sorted(names):
        if not path.startswith("av.libs/"):
            continue
        match = HASHED.fullmatch(path[len("av.libs/"):])
        if not match:
            continue
        family, version, _digest_suffix = match.groups()
        family = family.lower()
        if family in seen:
            raise ValueError("Duplicate hashed PyAV FFmpeg DLL family: " + family)
        seen.add(family)
        expected = f"{family}-{version}.dll"
        if expected not in names:
            raise ValueError("Matching release-root FFmpeg DLL absent: " + expected)
        pairs.append((expected, path))
    root = {n for n in names if ROOT.fullmatch(n)}
    if len(pairs) != 7 or seen != EXPECTED_FAMILIES:
        raise ValueError("Expected exactly seven hashed PyAV FFmpeg DLL families")
    if root != {item[0] for item in pairs}:
        raise ValueError("Root FFmpeg DLLs differ from PyAV version-matched group")
    return pairs


def section_info(raw: bytes) -> dict[str, str]:
    import pefile

    if pefile.__version__ != PINNED_PEFILE_VERSION:
        raise RuntimeError("Use pefile==" + PINNED_PEFILE_VERSION + " only in auditing")
    pe = pefile.PE(data=raw, fast_load=True)
    try:
        result = {}
        for section in pe.sections:
            name = section.Name.rstrip(b"\x00").decode("ascii", errors="replace")
            if name in result:
                raise ValueError("Duplicate PE section name: " + name)
            result[name] = sha(section.get_data())
        if ".text" not in result:
            raise ValueError("PE missing .text section")
        return result
    finally:
        pe.close()


def inspect(zip_path: Path, expected_sha256: str | None = None) -> dict:
    with zip_path.open("rb") as f:
        digest = hashlib.file_digest(f, "sha256").hexdigest()
    if expected_sha256 and digest != expected_sha256.lower():
        raise ValueError("Input runtime ZIP differs from pinned SHA-256")
    results = []
    with zipfile.ZipFile(zip_path) as z:
        for plain_path, pyav_path in paired_names(set(z.namelist())):
            plain = z.read(plain_path)
            pyav = z.read(pyav_path)
            a, b = section_info(plain), section_info(pyav)
            changed = sorted(k for k in (a.keys() | b.keys()) if a.get(k) != b.get(k))
            record = {
                "root_file": plain_path,
                "pyav_file": pyav_path,
                "root_sha256": sha(plain),
                "pyav_sha256": sha(pyav),
                "root_bytes": len(plain),
                "pyav_bytes": len(pyav),
                "files_byte_identical": plain == pyav,
                "files_same_size": len(plain) == len(pyav),
                "code_section_sha256": a[".text"],
                "text_section_identical": a[".text"] == b[".text"],
                "changed_section_names": changed,
                "all_sections_sha256_root": a,
                "all_sections_sha256_pyav": b,
            }
            results.append(record)
    if not all(item["text_section_identical"] for item in results):
        raise ValueError("PyAV and release-root FFmpeg code sections are not identical")
    if not all(item["files_same_size"] for item in results):
        raise ValueError("PyAV and root FFmpeg binary sizes differ")
    return {
        "schema": "sonicbrief-ffmpeg-duplicated-pe-section-v1",
        "zip_sha256": digest,
        "pairs": results,
        "summary": {
            "dll_pairs": len(results),
            "identical_text_sections": sum(r["text_section_identical"] for r in results),
            "identical_entire_files": sum(r["files_byte_identical"] for r in results),
            "same_file_sizes": sum(r["files_same_size"] for r in results),
        },
        "review_state": "BINARY_COMPARISON_ONLY__LEGAL_CLEARANCE_NOT_GRANTED",
        "limitations": [
            "Matching PE .text bytes are evidence of executable section identity, "
            "not full source reproducibility or linking/object map provenance.",
            "Non-code PE sections can be edited/rewritten, including DLL imports. "
            "This scan does not prove the DLLs are user-replaceable on Windows.",
            "Static LAME/AOM/VMAF and other third-party components, LGPL notices, "
            "corresponding source, relinkability and legal approval remain open.",
        ],
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--zip", type=Path, required=True)
    parser.add_argument("--expected-sha256", default=None)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = inspect(args.zip, args.expected_sha256)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    s = report["summary"]
    print("FFMPEG_PE_PAIR_PASS", "pairs", s["dll_pairs"],
          "identical_text", s["identical_text_sections"],
          "full_file_identical", s["identical_entire_files"],
          "LEGAL_CLEARANCE=NOT_CLEARED")


if __name__ == "__main__":
    main()
