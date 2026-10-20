"""Unit tests of matching plain and hashed FFmpeg DLL names."""
import unittest
from audit_ffmpeg_dll_pairs import paired_names, EXPECTED_FAMILIES


def filenames():
    result = {f"{name}-63.dll" for name in EXPECTED_FAMILIES}
    result.update(
        f"av.libs/{name}-63-{'a' * 32}.dll" for name in EXPECTED_FAMILIES
    )
    return result


class FfmpegPairTests(unittest.TestCase):
    def test_seven_version_matched_pairs(self):
        pairs = paired_names(filenames())
        self.assertEqual(len(pairs), 7)
        self.assertEqual({x.rsplit("-", 1)[0] for x, _ in pairs}, EXPECTED_FAMILIES)

    def test_reject_mismatched_version(self):
        names = filenames()
        names.remove("avcodec-63.dll")
        names.add("avcodec-61.dll")
        with self.assertRaisesRegex(ValueError, "absent"):
            paired_names(names)

    def test_reject_missing_private_dll(self):
        names = filenames()
        names.remove("av.libs/avutil-63-" + "a" * 32 + ".dll")
        with self.assertRaisesRegex(ValueError, "exactly seven"):
            paired_names(names)

    def test_reject_duplicate_hashed_candidate(self):
        names = filenames()
        names.add("av.libs/avcodec-63-" + "b" * 32 + ".dll")
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            paired_names(names)

    def test_reject_unmatched_root_dll(self):
        names = filenames()
        names.add("extra/avcodec-63.dll")
        # DLL in a third-party folder is not a root FFmpeg candidate
        self.assertEqual(len(paired_names(names)), 7)

    def test_reject_stray_root_ffmpeg_family(self):
        names = filenames()
        names.add("avcodec-62.dll")
        with self.assertRaisesRegex(ValueError, "Root FFmpeg"):
            paired_names(names)


if __name__ == "__main__":
    unittest.main()
