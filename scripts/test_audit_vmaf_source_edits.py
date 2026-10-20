"""Fast unit tests for the pinned BtbN VMAF source-edit replay audit."""
from __future__ import annotations

import tempfile
import unittest
import zipfile
from pathlib import Path

from audit_vmaf_source_edits import COMMANDS, exact_recipe_commands, load_pinned_inputs


class VmafReplayTests(unittest.TestCase):
    def test_pinned_recipe_operations(self):
        text = "\n".join((
            '#!/bin/bash',
            "ffbuild_dockerbuild() {",
            *["    " + cmd for cmd in COMMANDS],
            "}",
        ))
        self.assertEqual(exact_recipe_commands(text), COMMANDS)

    def test_reject_unexpected_source_edit_command(self):
        tampered = "\n".join(COMMANDS).replace(
            "libsvm_max(", "unexpected_max("
        )
        with self.assertRaisesRegex(ValueError, "differ from reviewed"):
            exact_recipe_commands(tampered)

    def test_rejects_missing_edit(self):
        with self.assertRaises(ValueError):
            exact_recipe_commands("\n".join(COMMANDS[:3]))

    def test_reject_wrong_source_archive_before_extracting(self):
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp) / "source.zip"
            with zipfile.ZipFile(source, "w") as archive:
                archive.writestr("unexpected/svm.cpp", "hello")
            with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
                load_pinned_inputs(source, Path(temp) / "recipes.zip")


if __name__ == "__main__":
    unittest.main()
