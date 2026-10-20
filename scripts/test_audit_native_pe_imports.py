"""Checks for SonicBrief's offline Windows PE import graph helper."""
from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from audit_native_pe_imports import (
    is_ffmpeg_dll, native_binary_paths, normalize_path, resolve_import,
)


class PeGraphTests(unittest.TestCase):
    def test_classifies_plain_and_repaired_ffmpeg_dlls(self):
        self.assertTrue(is_ffmpeg_dll("avcodec-63.dll"))
        self.assertTrue(is_ffmpeg_dll(
            "av.libs/avcodec-63-18de2bec466db9ec1afebff91d420ac6.dll"
        ))
        self.assertTrue(is_ffmpeg_dll("avutil-61.dll"))
        self.assertFalse(is_ffmpeg_dll("libmp3lame.dll"))
        self.assertFalse(is_ffmpeg_dll("libx264-164.dll"))

    def test_import_resolution_separates_hashed_private_dll(self):
        index = {
            "avcodec-63.dll": ["avcodec-63.dll"],
            "avcodec-63-18de2bec466db9ec1afebff91d420ac6.dll": [
                "av.libs/avcodec-63-18de2bec466db9ec1afebff91d420ac6.dll"
            ],
        }
        self.assertEqual(resolve_import(
            "ffmpeg.exe", "avcodec-63.dll", index
        )["path"], "avcodec-63.dll")
        self.assertEqual(resolve_import(
            "av/bitstream.pyd",
            "avcodec-63-18de2bec466db9ec1afebff91d420ac6.dll", index
        )["path"], "av.libs/avcodec-63-18de2bec466db9ec1afebff91d420ac6.dll")

    def test_ambiguous_matches_do_not_claim_resolution(self):
        index = {"codec.dll": ["x/codec.dll", "y/codec.dll"]}
        result = resolve_import("av/codec.pyd", "CODEC.dll", index)
        self.assertEqual(result["kind"], "ambiguous-bundled-import")
        self.assertEqual(len(result["paths"]), 2)

    def test_not_in_bundle_stays_unresolved(self):
        result = resolve_import("ffmpeg.exe", "kernel32.dll", {})
        self.assertEqual(result["kind"], "not-in-bundle")

    def test_scans_native_extensions_only(self):
        actual = native_binary_paths([
            "ffmpeg.exe", "av.libs/avcodec-63.dll", "av/stream.pyd",
            "README.md", "LICENSE", "third_party/source.tar.gz",
        ])
        self.assertEqual(len(actual), 3)

    def test_filename_normalization(self):
        self.assertEqual(normalize_path("Av.Libs\\Codec.DLL"), "av.libs/codec.dll")


if __name__ == "__main__":
    unittest.main()
