"""Unit tests for the LGPL-only portable release policy scanner."""
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from check_portable_gpl_codecs import find_flagged_codecs


class PortableCodecPolicyTests(unittest.TestCase):
    def test_detects_pyav_gpl_codec_binaries(self):
        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            libs = root / "av.libs"
            libs.mkdir()
            for name in ("libx264-165-abc.dll", "libx265-def.dll", "avcodec-62.dll"):
                (libs / name).write_bytes(b"test")
            self.assertEqual(
                find_flagged_codecs(root),
                ["av.libs/libx264-165-abc.dll", "av.libs/libx265-def.dll"],
            )

    def test_clean_runtime_has_no_flags(self):
        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "avcodec-62.dll").write_bytes(b"test")
            self.assertEqual(find_flagged_codecs(root), [])

    def test_missing_directory_is_not_a_pass(self):
        with TemporaryDirectory() as tmp:
            with self.assertRaises(FileNotFoundError):
                find_flagged_codecs(Path(tmp) / "missing")


if __name__ == "__main__":
    unittest.main()
