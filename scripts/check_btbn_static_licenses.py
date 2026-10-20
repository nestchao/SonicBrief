"""Validate pinned license/notice texts for statically linked BtbN FFmpeg dependencies.

Passing this check proves file identity and presence ONLY; it does not provide
corresponding source, linking objects, notices for every component, or legal clearance.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

REQUIRED = frozenset({
    "lame/LICENSE",
    "lame/COPYING",
    "dav1d/COPYING",
    "aom/LICENSE",
    "aom/PATENTS",
    "vmaf/LICENSE",
})


def verify_license_bundle(root: Path) -> dict:
    path = root / "MANIFEST.json"
    if not path.is_file():
        raise ValueError(f"Missing BtbN notice manifest: {path}")
    data = json.loads(path.read_text(encoding="utf-8"))
    if data.get("schema") != "sonicbrief-btbn-static-license-evidence-v1":
        raise ValueError("Unexpected BtbN license manifest schema")
    entries = data.get("entries")
    if not isinstance(entries, list):
        raise ValueError("Missing BtbN license manifest entries")
    names = [e.get("path") for e in entries if isinstance(e, dict)]
    if len(names) != len(entries) or len(set(names)) != len(names):
        raise ValueError("Invalid or duplicate BtbN license entries")
    if set(names) != REQUIRED:
        raise ValueError(f"BtbN license manifest differs from required file set: {set(names) ^ REQUIRED}")
    for entry in entries:
        name = entry["path"]
        relative = Path(name)
        if relative.is_absolute() or ".." in relative.parts or "\\" in name:
            raise ValueError(f"Unsafe BtbN license path: {name}")
        source = root / relative
        if not source.is_file():
            raise ValueError(f"Missing pinned upstream license text: {source}")
        content = source.read_bytes()
        expected = entry.get("sha256")
        if not isinstance(expected, str) or hashlib.sha256(content).hexdigest() != expected:
            raise ValueError(f"Checksum mismatch for pinned upstream notice: {name}")
        if len(content) != entry.get("bytes"):
            raise ValueError(f"Size mismatch for pinned upstream notice: {name}")
        if not entry.get("upstream_url") or not entry.get("source_revision"):
            raise ValueError(f"Provenance missing for {name}")
    return data


def main() -> None:
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--directory",
        type=Path,
        default=Path(__file__).resolve().parents[1] / "third_party" / "btbn_static_licenses",
    )
    args = parser.parse_args()
    result = verify_license_bundle(args.directory)
    print(f"PASS {len(result['entries'])} pinned upstream notices; "
          "source/relinking/license compliance STILL NOT CLEARED")


if __name__ == "__main__":
    main()
