"""Run with: .venv\\Scripts\\python.exe -m unittest tests.test_download_settings"""

import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "backend"))

import download_settings  # noqa: E402

COOKIES = (
    "# Netscape HTTP Cookie File\n"
    "#HttpOnly_.youtube.com\tTRUE\t/\tTRUE\t9999999999\tSID\tabc123\n"
    ".youtube.com\tTRUE\t/\tFALSE\t9999999999\tPREF\tlang=en\n"
    ".example.com\tTRUE\t/\tFALSE\t9999999999\tTRACKER\tnope\n"
)


class ModeTests(unittest.TestCase):
    def test_defaults_to_auto_when_nothing_is_saved(self):
        with mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop("SONICBRIEF_YTDLP_COOKIES_FROM_BROWSER", None)
            os.environ.pop("YTDLP_BROWSER", None)
            self.assertEqual(download_settings.get_mode(), "auto")

    def test_ytdlp_browser_fallback_is_honoured(self):
        with mock.patch.dict(os.environ, {"YTDLP_BROWSER": "edge"}, clear=False):
            os.environ.pop("SONICBRIEF_YTDLP_COOKIES_FROM_BROWSER", None)
            self.assertEqual(download_settings.get_mode(), "edge")

    def test_explicit_choice_wins_including_off(self):
        env = {"YTDLP_BROWSER": "edge", "SONICBRIEF_YTDLP_COOKIES_FROM_BROWSER": "off"}
        with mock.patch.dict(os.environ, env, clear=False):
            self.assertEqual(download_settings.get_mode(), "off")
        env["SONICBRIEF_YTDLP_COOKIES_FROM_BROWSER"] = "firefox"
        with mock.patch.dict(os.environ, env, clear=False):
            self.assertEqual(download_settings.get_mode(), "firefox")

    def test_unknown_values_fall_back_to_auto(self):
        with mock.patch.dict(os.environ, {"YTDLP_BROWSER": "netscape"}, clear=False):
            os.environ.pop("SONICBRIEF_YTDLP_COOKIES_FROM_BROWSER", None)
            self.assertEqual(download_settings.get_mode(), "auto")

    def test_save_mode_rejects_unknown_values(self):
        with self.assertRaises(ValueError):
            download_settings.save_mode("netscape")


class ChainTests(unittest.TestCase):
    def _chain(self, mode, installed, has_file, last_good=None):
        env = {"SONICBRIEF_YTDLP_COOKIES_FROM_BROWSER": mode}
        with mock.patch.dict(os.environ, env, clear=False), \
             mock.patch.object(download_settings, "installed_browsers", return_value=installed), \
             mock.patch.object(download_settings, "has_cookie_file", return_value=has_file), \
             mock.patch.object(download_settings, "last_good_source", return_value=last_good):
            return download_settings.source_chain()

    def test_off_means_no_sources(self):
        self.assertEqual(self._chain("off", ["firefox"], True), [])

    def test_explicit_browser_is_used_alone(self):
        self.assertEqual(self._chain("chrome", ["firefox"], True), [("browser", "chrome")])

    def test_file_mode_requires_a_file(self):
        self.assertEqual(self._chain("file", [], False), [])
        chain = self._chain("file", [], True)
        self.assertEqual(chain[0][0], "file")

    def test_auto_puts_firefox_first_then_file_then_chromium(self):
        chain = self._chain("auto", ["firefox", "chrome", "edge"], True)
        self.assertEqual(
            [value if kind == "browser" else "file" for kind, value in chain],
            ["firefox", "file", "chrome", "edge"],
        )

    def test_auto_skips_browsers_that_are_not_installed(self):
        chain = self._chain("auto", ["edge"], False)
        self.assertEqual(chain, [("browser", "edge")])

    def test_last_good_source_moves_to_the_front(self):
        chain = self._chain("auto", ["firefox", "chrome"], True, last_good="chrome")
        self.assertEqual(chain[0], ("browser", "chrome"))
        self.assertIn(("browser", "firefox"), chain)

    def test_last_good_file_source_moves_to_the_front(self):
        chain = self._chain("auto", ["firefox"], True, last_good="file")
        self.assertEqual(chain[0][0], "file")


class CookieFileTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        target = Path(self.temp_dir.name) / "youtube-cookies.txt"
        self.patcher = mock.patch.multiple(
            download_settings,
            _COOKIE_DIR=Path(self.temp_dir.name),
            _COOKIE_FILE=target,
        )
        self.patcher.start()
        self.addCleanup(self.patcher.stop)

    def test_keeps_only_youtube_and_google_rows(self):
        kept = download_settings.save_cookie_file(COOKIES.encode("utf-8"))
        self.assertEqual(kept, 2)
        stored = download_settings.cookie_file_path().read_text(encoding="utf-8")
        self.assertIn("SID", stored)
        self.assertIn("PREF", stored)
        self.assertNotIn("example.com", stored)
        self.assertNotIn("TRACKER", stored)

    def test_rejects_non_netscape_text(self):
        with self.assertRaises(ValueError):
            download_settings.save_cookie_file(b"<html>not cookies</html>")

    def test_rejects_binary(self):
        with self.assertRaises(ValueError):
            download_settings.save_cookie_file(b"\x00\x01\x02binary")

    def test_rejects_oversized_files(self):
        payload = b".youtube.com\tTRUE\t/\tFALSE\t0\tA\tb\n" * 60000
        with self.assertRaises(ValueError):
            download_settings.save_cookie_file(payload)

    def test_rejects_cookies_for_other_sites(self):
        with self.assertRaises(ValueError):
            download_settings.save_cookie_file(b".example.com\tTRUE\t/\tFALSE\t0\tA\tb\n")

    def test_delete_reports_whether_a_file_existed(self):
        download_settings.save_cookie_file(COOKIES.encode("utf-8"))
        self.assertTrue(download_settings.delete_cookie_file())
        self.assertFalse(download_settings.delete_cookie_file())


class DescribeTests(unittest.TestCase):
    def test_describe_never_returns_cookie_contents(self):
        described = download_settings.describe()
        self.assertIn("mode", described)
        self.assertIn("choices", described)
        rendered = str(described).lower()
        self.assertNotIn("sid\t", rendered)
        self.assertNotIn("lang=en", rendered)


class JsRuntimeTests(unittest.TestCase):
    def test_prefers_deno_then_node(self):
        with mock.patch("shutil.which", side_effect=lambda n: {"deno": "D", "node": "N"}.get(n)):
            self.assertEqual(download_settings.js_runtimes(), {"deno": {"path": "D"}})
        with mock.patch("shutil.which", side_effect=lambda n: {"node": "N"}.get(n)):
            self.assertEqual(download_settings.js_runtimes(), {"node": {"path": "N"}})
        with mock.patch("shutil.which", return_value=None):
            self.assertEqual(download_settings.js_runtimes(), {})


if __name__ == "__main__":
    unittest.main()
