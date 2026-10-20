"""Unit tests of private FFmpeg DLL substitution on synthetic files."""
from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from smoke_test_ffmpeg_dll_replacement import (
    FAMILIES, dll_pairs, file_hash, substitute_private_dlls,
)


def fixture(root: Path) -> None:
    (root / "av.libs").mkdir()
    (root / "SonicBrief-MCP.exe").write_bytes(b"EXE")
    for name in FAMILIES:
        (root / (name + "-63.dll")).write_bytes((name + "-root").encode())
        (root / "av.libs" / (name + "-63-" + "a" * 32 + ".dll")).write_bytes(
            (name + "-private").encode()
        )


class SubstitutionTests(unittest.TestCase):
    def test_seven_pairs(self):
        with tempfile.TemporaryDirectory() as t:
            root = Path(t)
            fixture(root)
            self.assertEqual(len(dll_pairs(root)), 7)

    def test_seven_replacements_preserve_root_files(self):
        with tempfile.TemporaryDirectory() as t:
            root = Path(t)
            fixture(root)
            expected = {src.name: file_hash(src) for src, _ in dll_pairs(root)}
            changes = substitute_private_dlls(root)
            self.assertEqual(len(changes), 7)
            self.assertTrue(all(not c["before_already_identical"] for c in changes))
            self.assertTrue(all(
                c["source_plain_sha256"] == c["substituted_private_sha256"]
                for c in changes
            ))
            self.assertEqual(
                expected, {src.name: file_hash(src) for src, _ in dll_pairs(root)}
            )

    def test_missing_dll_is_rejected(self):
        with tempfile.TemporaryDirectory() as t:
            root = Path(t)
            fixture(root)
            (root / "avcodec-63.dll").unlink()
            with self.assertRaisesRegex(ValueError, "Missing matching"):
                dll_pairs(root)

    def test_duplicate_family_rejected(self):
        with tempfile.TemporaryDirectory() as t:
            root = Path(t)
            fixture(root)
            (root / "av.libs" / ("avcodec-63-" + "b" * 32 + ".dll")).write_bytes(b"x")
            with self.assertRaisesRegex(ValueError, "Duplicate"):
                dll_pairs(root)

    def test_absent_runtime_exe_rejected(self):
        with tempfile.TemporaryDirectory() as t:
            root = Path(t)
            fixture(root)
            (root / "SonicBrief-MCP.exe").unlink()
            with self.assertRaisesRegex(ValueError, "Not a complete"):
                dll_pairs(root)

    def test_second_identical_substitution_is_idempotent(self):
        with tempfile.TemporaryDirectory() as t:
            root = Path(t)
            fixture(root)
            substitute_private_dlls(root)
            again = substitute_private_dlls(root)
            self.assertTrue(all(x["before_already_identical"] for x in again))


if __name__ == "__main__":
    unittest.main()
