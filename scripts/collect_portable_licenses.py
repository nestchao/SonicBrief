"""Audit bundled Python package metadata and record license-file provenance.

This is a conservative build-time file-presence check, not a legal opinion.
It reports actual PyInstaller metadata and refuses to ship dependencies with
no detectable license file or independently reviewed version-pinned fallback.
"""
from __future__ import annotations

import json
from email.parser import Parser
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PORTABLE = ROOT / "build" / "portable" / "SonicBrief-MCP"
FALLBACKS = ROOT / "third_party" / "python_licenses"


def is_license_file(p: Path) -> bool:
    return p.is_file() and p.name.upper().startswith(
        ("LICENSE", "LICENCE", "COPYING", "NOTICE")
    )


def main() -> None:
    if not PORTABLE.is_dir():
        raise RuntimeError(f"Missing packaged portable runtime: {PORTABLE}")
    entries: list[dict[str, object]] = []
    missing: list[str] = []
    info_dirs = sorted(PORTABLE.glob("*.dist-info"))
    if len(info_dirs) < 10:
        raise RuntimeError("Unexpectedly few bundled Python package metadata directories.")

    for info_dir in info_dirs:
        metadata_file = info_dir / "METADATA"
        if not metadata_file.is_file():
            raise RuntimeError(f"Missing Python metadata: {metadata_file}")
        meta = Parser().parsestr(metadata_file.read_text(encoding="utf-8"))
        name = meta.get("Name", "")
        version = meta.get("Version", "")
        license_label = meta.get("License-Expression") or meta.get("License") or "UNSPECIFIED"
        if not name or not version:
            raise RuntimeError(f"Missing package name/version in {metadata_file}")
        packaged_licenses = [
            str(p.relative_to(PORTABLE)).replace("\\", "/")
            for p in info_dir.rglob("*") if is_license_file(p)
        ]
        fallback_path = FALLBACKS / f"{name.lower().replace('_', '-')}-{version}" / "LICENSE"
        fallback = (
            f"third_party/python_licenses/{fallback_path.parent.name}/LICENSE"
            if fallback_path.is_file() else None
        )
        if not packaged_licenses and not fallback:
            missing.append(f"{name}=={version}")

        entries.append({
            "name": name,
            "version": version,
            "license_metadata": license_label,
            "wheel_license_files": packaged_licenses,
            "reviewed_fallback_license": fallback,
        })

    report = {
        "generated_by": "scripts/collect_portable_licenses.py",
        "note": (
            "This inventory only covers metadata preserved by PyInstaller. "
            "Other native transitive libraries and redistribution/source "
            "obligations still require review."
        ),
        "python_runtime_license": "third_party/python_licenses/cpython-3.11.9/LICENSE",
        "packages": entries,
        "missing_license_files": missing,
    }
    out = PORTABLE / "third_party" / "python"
    out.mkdir(parents=True, exist_ok=True)
    (out / "INVENTORY.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"Audited {len(entries)} PyInstaller Python metadata distributions.")
    for entry in entries:
        print(f"  {entry['name']}=={entry['version']}  {entry['license_metadata']}")
    if missing:
        raise RuntimeError("Unreviewed Python wheel license files: " + ", ".join(missing))


if __name__ == "__main__":
    main()
