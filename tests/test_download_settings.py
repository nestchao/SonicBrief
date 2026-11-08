"""Run with: .venv\\Scripts\\python.exe -m unittest tests.test_download_settings"""

import os
import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "backend"))

import download_settings  # noqa: E402


class BrowserChoiceTests(unittest.TestCase):
    def test_falls_back_to_ytdlp_browser(self):
        with mock.patch.dict(os.environ, {"YTDLP_BROWSER": "edge"}, clear=False):
            os.environ.pop("SONICBRIEF_YTDLP_COOKIES_FROM_BROWSER", None)
            self.assertEqual(download_settings.get_browser(), "edge")

    def test_explicit_choice_wins_including_off(self):
        env = {"YTDLP_BROWSER": "edge", "SONICBRIEF_YTDLP_COOKIES_FROM_BROWSER": "off"}
        with mock.patch.dict(os.environ, env, clear=False):
            self.assertEqual(download_settings.get_browser(), "off")
        env["SONICBRIEF_YTDLP_COOKIES_FROM_BROWSER"] = "firefox"
        with mock.patch.dict(os.environ, env, clear=False):
            self.assertEqual(download_settings.get_browser(), "firefox")

    def test_unknown_values_mean_off(self):
        env = {"YTDLP_BROWSER": "netscape"}
        with mock.patch.dict(os.environ, env, clear=False):
            os.environ.pop("SONICBRIEF_YTDLP_COOKIES_FROM_BROWSER", None)
            self.assertEqual(download_settings.get_browser(), "off")

    def test_js_runtime_prefers_deno_then_node(self):
        with mock.patch("shutil.which", side_effect=lambda n: {"deno": "D", "node": "N"}.get(n)):
            self.assertEqual(download_settings.js_runtimes(), {"deno": {"path": "D"}})
        with mock.patch("shutil.which", side_effect=lambda n: {"node": "N"}.get(n)):
            self.assertEqual(download_settings.js_runtimes(), {"node": {"path": "N"}})
        with mock.patch("shutil.which", return_value=None):
            self.assertEqual(download_settings.js_runtimes(), {})


if __name__ == "__main__":
    unittest.main()
