"""Unit tests for the version-pinned FFmpeg static-dependency notice bundle."""
from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from check_btbn_static_licenses import REQUIRED, verify_license_bundle


def fixture(directory: Path) -> None:
    entries = []
    for name in sorted(REQUIRED):
        path = directory / name
        path.parent.mkdir(parents=True, exist_ok=True)
        data = ("license " + name).encode("utf-8")
        path.write_bytes(data)
        entries.append({
            "path": name,
            "upstream_url": "https://example.org/source",
            "source_revision": "test-pinned",
            "sha256": hashlib.sha256(data).hexdigest(),
            "bytes": len(data),
        })
    (directory / "MANIFEST.json").write_text(
        json.dumps({"schema": "sonicbrief-btbn-static-license-evidence-v1",
                    "entries": entries}), encoding="utf-8"
    )


class BundleTests(unittest.TestCase):
    def test_verified_bundle(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            fixture(root)
            result = verify_license_bundle(root)
            self.assertEqual(len(result["entries"]), len(REQUIRED))

    def test_tampered_patent_text_blocks(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            fixture(root)
            (root / "aom/PATENTS").write_text("tampered", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "Checksum mismatch"):
                verify_license_bundle(root)

    def test_missing_library_notice_blocks(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            fixture(root)
            (root / "dav1d/COPYING").unlink()
            with self.assertRaisesRegex(ValueError, "Missing pinned"):
                verify_license_bundle(root)

    def test_manifest_missing_patent_entry_blocks(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            fixture(root)
            manifest_path = root / "MANIFEST.json"
            data = json.loads(manifest_path.read_text(encoding="utf-8"))
            data["entries"] = [e for e in data["entries"] if e["path"] != "aom/PATENTS"]
            manifest_path.write_text(json.dumps(data), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "required file set"):
                verify_license_bundle(root)


if __name__ == "__main__":
    unittest.main()
