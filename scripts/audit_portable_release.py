"""Create a checksummed, evidence-based inventory of a SonicBrief portable Release.

This is NOT legal clearance, a corresponding-source archive, or a complete SBOM.
Run only against the exact release ZIP and the matching CI-generated npm tarball.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import tarfile
import zipfile
from pathlib import Path

SOURCE_INFO = {
    "ffmpeg_distribution": "BtbN FFmpeg-Builds LGPL shared Windows x64",
    "ffmpeg_build_tag": "autobuild-2026-09-30-13-08",
    "ffmpeg_build_repo_commit": "6c9aec5fc9a72ec3abedd1fa84db141fa18cf52b",
    "ffmpeg_revision_reported_by_binary": "n9.0.2-17-g2a571b6068",
    "ffmpeg_verified_source_commit": "2a571b606854520cf89804d8030c8b328e621689",
    "ffmpeg_source_snapshot": "https://github.com/FFmpeg/FFmpeg/tree/2a571b606854520cf89804d8030c8b328e621689",
    "ffmpeg_binary_asset": "ffmpeg-n9.0.2-17-g2a571b6068-win64-lgpl-shared-9.0.zip",
    "ffmpeg_asset_sha256": "7157177b8a6cb2174c1650ba8c71b363f2c78cba5330f88c4c02cf5b2b880646",
    "ffmpeg_upstream_source": "https://git.ffmpeg.org/ffmpeg.git",
    "ffmpeg_license_guidance": "https://ffmpeg.org/legal.html",
    "build_recipes": "https://github.com/BtbN/FFmpeg-Builds/tree/6c9aec5fc9a72ec3abedd1fa84db141fa18cf52b",
    "binary_release": "https://github.com/BtbN/FFmpeg-Builds/releases/tag/autobuild-2026-09-30-13-08",
}

REQUIRED = {
    "SonicBrief-MCP.exe",
    "ffmpeg.exe",
    "ffprobe.exe",
    "LICENSE",
    "THIRD_PARTY_NOTICES.md",
    "third_party/ffmpeg/LICENSE.txt",
    "third_party/python/INVENTORY.json",
    "third_party/python_licenses/cpython-3.11.9/LICENSE",
}
NATIVE_SUFFIXES = (".exe", ".dll", ".pyd", ".so", ".dylib")


def sha256_path(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def sha256_zip_entry(archive: zipfile.ZipFile, name: str) -> str:
    h = hashlib.sha256()
    with archive.open(name) as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def make_report(zip_path: Path, tgz_path: Path, tag: str) -> dict:
    archive_sha = sha256_path(zip_path)
    with tarfile.open(tgz_path, "r:gz") as t:
        manifest_member = t.extractfile("package/runtime-manifest.json")
        package_member = t.extractfile("package/package.json")
        if manifest_member is None or package_member is None:
            raise ValueError("npm package manifest or package.json missing")
        manifest = json.load(manifest_member)
        package = json.load(package_member)
    expected_url = (
        "https://github.com/nestchao/SonicBrief/releases/download/"
        f"{tag}/sonicbrief-windows-x64-cpu.zip"
    )
    if manifest["sha256"].lower() != archive_sha:
        raise ValueError("npm manifest SHA-256 != actual runtime ZIP")
    if manifest["url"] != expected_url:
        raise ValueError("npm manifest URL does not match the exact release tag")
    if tag != f"mcp-v{package['version']}":
        raise ValueError("npm version does not match the release tag")

    with zipfile.ZipFile(zip_path) as archive:
        names = set(archive.namelist())
        missing = sorted(REQUIRED - names)
        if missing:
            raise ValueError("Missing required archive files: " + ", ".join(missing))
        inventory = json.loads(archive.read("third_party/python/INVENTORY.json"))
        if inventory.get("missing_license_files"):
            raise ValueError("Python license inventory reports missing license files")
        if len(inventory.get("packages", [])) < 10:
            raise ValueError("Suspiciously small Python license inventory")
        license_text = archive.read("third_party/ffmpeg/LICENSE.txt").decode("utf-8", errors="replace")
        if "GNU LESSER GENERAL PUBLIC LICENSE" not in license_text:
            raise ValueError("Expected FFmpeg LGPLv3 license text absent")
        native_names = sorted(n for n in names if n.lower().endswith(NATIVE_SUFFIXES))
        flagged_gpl_codecs = [
            n for n in native_names
            if n.rsplit('/', 1)[-1].lower().startswith(('libx264-', 'libx265-', 'libx264.', 'libx265.'))
        ]
        licenses = sorted(
            n for n in names if
            n.rsplit("/", 1)[-1].upper().startswith(("LICENSE", "LICENCE", "COPYING", "NOTICE"))
        )
        native_binaries = [
            {"path": n, "bytes": archive.getinfo(n).file_size,
             "sha256": sha256_zip_entry(archive, n)}
            for n in native_names
        ]
    return {
        "schema": "sonicbrief-portable-provenance-v1",
        "release_tag": tag,
        "npm_package": f"{package['name']}@{package['version']}",
        "npm_manifest_url": expected_url,
        "runtime_zip": zip_path.name,
        "runtime_zip_sha256": archive_sha,
        "runtime_zip_size_bytes": zip_path.stat().st_size,
        "source_build_identifiers": SOURCE_INFO,
        "python_distribution_inventory": inventory,
        "included_license_and_notice_paths": licenses,
        "native_binary_count": len(native_binaries),
        "native_binary_sha256": native_binaries,
        "known_gpl_associated_codec_binaries": flagged_gpl_codecs,
        "review_state": {
            "automated_checksum_and_license_presence_checks": "passed",
            "complete_native_and_transitive_source_audit": "pending_human_review",
            "lgplv3_corresponding_source_and_relinkability_review": "pending_human_review",
            "license_compliance_clearance": "NOT_CLEARED",
            "public_release_approved": False,
        },
        "limitations": [
            "Hashes/metadata prove packaged file identity, not licensing permission.",
            "PyInstaller may contain native components not identified by filename.",
            "Upstream FFmpeg and build-script links are pointers, not a complete corresponding-source bundle.",
            "Exact transitive dependency sources/build instructions and applicable licenses require manual review.",
            "Model weights downloaded on first use are not part of this archive.",
        ],
    }


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--zip", type=Path, required=True)
    p.add_argument("--npm-tgz", type=Path, required=True)
    p.add_argument("--tag", default="mcp-v0.1.0")
    p.add_argument("--output", type=Path, required=True)
    args = p.parse_args()
    data = make_report(args.zip, args.npm_tgz, args.tag)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print("PASS release ZIP/package provenance:", data["runtime_zip_sha256"])
    print("Native files:", data["native_binary_count"])
    print("Python packages:", len(data["python_distribution_inventory"]["packages"]))
    print("Legal review state:", data["review_state"]["license_compliance_clearance"])


if __name__ == "__main__":
    main()
