"""Regression coverage for packaged backend startup timeout configuration."""
import unittest

from backend_startup_timeout import backend_startup_timeout_seconds


class StartupTimeoutTests(unittest.TestCase):
    def test_packaged_default_covers_cold_windows_start(self):
        self.assertEqual(backend_startup_timeout_seconds(True, {}), 90.0)

    def test_native_and_docker_default_is_unchanged(self):
        self.assertEqual(backend_startup_timeout_seconds(False, {}), 30.0)

    def test_explicit_override(self):
        env = {"SONICBRIEF_BACKEND_STARTUP_TIMEOUT_SECONDS": "120.5"}
        self.assertEqual(backend_startup_timeout_seconds(True, env), 120.5)
        self.assertEqual(backend_startup_timeout_seconds(False, env), 120.5)

    def test_invalid_values_rejected(self):
        for val in ("NaN", "Infinity", "-10", "0", "4.9", "300.1", "oops"):
            with self.subTest(value=val):
                with self.assertRaises(RuntimeError):
                    backend_startup_timeout_seconds(
                        True, {"SONICBRIEF_BACKEND_STARTUP_TIMEOUT_SECONDS": val}
                    )

    def test_bounds_accepted(self):
        for val in ("5", "300"):
            with self.subTest(value=val):
                self.assertEqual(
                    backend_startup_timeout_seconds(
                        True, {"SONICBRIEF_BACKEND_STARTUP_TIMEOUT_SECONDS": val}
                    ), float(val)
                )


if __name__ == "__main__":
    unittest.main()
