"""Run with: .venv\\Scripts\\python.exe -m unittest tests.test_groq_settings"""

import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "backend"))

import groq_settings  # noqa: E402


class ModelTests(unittest.TestCase):
    def test_only_two_whisper_models_allowed(self):
        self.assertEqual(groq_settings.normalize_groq_model_id("whisper-large-v3-turbo"), "whisper-large-v3-turbo")
        self.assertEqual(groq_settings.normalize_groq_model_id("whisper-large-v3"), "whisper-large-v3")
        with self.assertRaises(ValueError):
            groq_settings.normalize_groq_model_id("whisper-large-v3-turbo-2")
        with self.assertRaises(ValueError):
            groq_settings.normalize_groq_model_id("gemini-flash-lite-latest")

    def test_default_model_is_turbo(self):
        self.assertEqual(groq_settings.DEFAULT_GROQ_MODEL, "whisper-large-v3-turbo")

    def test_available_models_lists_both_with_labels(self):
        models = groq_settings.available_groq_models()
        ids = [item["id"] for item in models]
        self.assertEqual(ids, ["whisper-large-v3-turbo", "whisper-large-v3"])
        for item in models:
            self.assertTrue(item["label"])
            self.assertTrue(item["description"])

    def test_save_model_rejects_unknown_ids(self):
        with self.assertRaises(ValueError):
            groq_settings.save_groq_model("not-a-model")


class StatusTests(unittest.TestCase):
    def test_not_configured_without_key(self):
        with mock.patch.object(groq_settings, "get_groq_api_key", return_value=""):
            self.assertFalse(groq_settings.is_configured())

    def test_configured_with_key(self):
        with mock.patch.object(groq_settings, "get_groq_api_key", return_value="gsk_test"):
            self.assertTrue(groq_settings.is_configured())


if __name__ == "__main__":
    unittest.main()
