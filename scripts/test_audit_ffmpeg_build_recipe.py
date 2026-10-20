"""Tests for the static BtbN FFmpeg recipe cross-reference audit."""
from __future__ import annotations

import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from audit_ffmpeg_build_recipe import inspect_recipe_archive, make_markdown


class FFmpegRecipeEvidenceTests(unittest.TestCase):
    def _archive(self, location: Path, include_variant: bool = True) -> None:
        prefix = "FFmpeg-Builds-test/"
        items = {
            "scripts.d/50-libfoo.sh": (
                'SCRIPT_REPO="https://example.org/libfoo.git"\n'
                'SCRIPT_COMMIT="abcdef123456"\n'
                'ffbuild_configure() {\n    echo --enable-libfoo\n}\n'
            ),
            "scripts.d/50-libx264.sh": (
                'ffbuild_enabled() { [[ $VARIANT == lgpl* ]] && return -1; return 0; }\n'
                'ffbuild_configure() {\n    echo --enable-libx264\n}\n'
            ),
            "build.sh": "source variants/win64-lgpl-shared.sh\n",
        }
        if include_variant:
            items.update({
                "variants/win64-lgpl-shared.sh": "source defaults-lgpl-shared.sh\n",
                "variants/defaults-lgpl-shared.sh": "FF_CONFIGURE+=' --enable-shared --disable-static'\n",
                "variants/defaults-lgpl.sh": 'LICENSE_FILE="COPYING.LGPLv3"\n',
            })
        with zipfile.ZipFile(location, "w") as dest:
            for name, text in items.items():
                dest.writestr(prefix + name, text)

    def test_recipe_hints_and_pins_are_discovered(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "build.zip"
            self._archive(path)
            recipes, _ = inspect_recipe_archive(path)
        self.assertEqual(
            recipes["libfoo"][0]["source_commit"], "abcdef123456"
        )
        self.assertTrue(recipes["libx264"][0]["conditional_build"])

    def test_missing_variant_is_an_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "build.zip"
            self._archive(path, include_variant=False)
            with self.assertRaises(ValueError):
                inspect_recipe_archive(path)

    def test_report_explicitly_disclaims_legal_clearance(self):
        summary = {
            "expected_variant": "win64-lgpl-shared",
            "declared_license": "LGPLv3",
            "enabled_flag_count": 1,
            "matching_recipe_flag_count": 0,
            "variant_flag_count": 0,
            "unmatched_flag_count": 1,
            "btbn_recipes_sha256": "abc",
            "ffmpeg_binary_sha256": "def",
            "flags": [{
                "enabled_flag": "--enable-version3",
                "recipe_candidates": [],
            }],
            "disclaimer": "Not legal clearance.",
        }
        result = make_markdown(summary)
        self.assertIn("NOT CLEARED", result)
        self.assertIn("Not legal clearance.", result)
        self.assertIn("--enable-version3", result)


if __name__ == "__main__":
    unittest.main()
