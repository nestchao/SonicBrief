"""Run with: .venv\\Scripts\\python.exe -m unittest discover -s tests -p "test_cuda_guard.py"

Regression tests for the "stuck at 25%" class of failure: the app reported CUDA as
available (the driver enumerated a GPU) while cuBLAS/cuDNN were not installed, so
ctranslate2 failed inside a native call and the job never advanced again.
"""

import datetime
import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "backend"))

import pipeline  # noqa: E402

NOW = datetime.datetime(2026, 11, 11, 16, 20, tzinfo=datetime.timezone.utc)


class CudaGuardTests(unittest.TestCase):
    def setUp(self):
        pipeline._CUDA_LIB_CACHE.clear()
        pipeline._CUDA_RUNTIME_FAILURE = None

    tearDown = setUp

    def test_missing_math_libs_reported_even_when_a_device_exists(self):
        with mock.patch("ctypes.WinDLL", side_effect=OSError("not found")), \
             mock.patch.object(pipeline.ctranslate2, "get_cuda_device_count", return_value=1):
            self.assertFalse(pipeline.cuda_available())
            reason = pipeline.cuda_unavailable_reason()
            self.assertIsNotNone(reason)
            self.assertIn("CUDA pack", reason)

    def test_usable_libs_keep_cuda_enabled(self):
        with mock.patch("ctypes.WinDLL", return_value=object()), \
             mock.patch.object(pipeline.ctranslate2, "get_cuda_device_count", return_value=1):
            self.assertTrue(pipeline.cuda_available())
            self.assertIsNone(pipeline.cuda_unavailable_reason())

    def test_runtime_failure_downgrades_to_cpu_for_later_jobs(self):
        pipeline.note_cuda_failure("Library cublas64_12.dll is not found or cannot be loaded")
        self.assertFalse(pipeline.cuda_available())
        self.assertIn("cublas64_12", pipeline.cuda_unavailable_reason())

    def test_force_cpu_env_is_honoured(self):
        with mock.patch.dict(pipeline.os.environ, {"SONICBRIEF_FORCE_CPU": "1"}):
            self.assertFalse(pipeline.cuda_available())
            self.assertIn("SONICBRIEF_FORCE_CPU", pipeline.cuda_unavailable_reason())

    def test_cuda_failure_detection_matches_real_messages(self):
        self.assertTrue(pipeline.looks_like_cuda_failure(RuntimeError(
            "Library cublas64_12.dll is not found or cannot be loaded")))
        self.assertTrue(pipeline.looks_like_cuda_failure(RuntimeError("CUDA error: out of memory")))
        self.assertFalse(pipeline.looks_like_cuda_failure(RuntimeError("Media download failed")))


class StallDetectionTests(unittest.TestCase):
    def setUp(self):
        pipeline._CUDA_LIB_CACHE.clear()
        pipeline._CUDA_RUNTIME_FAILURE = None

    def test_stalled_seconds_parses_naive_and_aware_stamps(self):
        self.assertAlmostEqual(pipeline.stalled_seconds("2026-11-11T16:05:00", NOW), 900.0, places=1)
        self.assertAlmostEqual(pipeline.stalled_seconds("2026-11-11T16:05:00+00:00", NOW), 900.0, places=1)
        self.assertIsNone(pipeline.stalled_seconds(None, NOW))
        self.assertIsNone(pipeline.stalled_seconds("not-a-date", NOW))

    def test_job_without_progress_is_stalled(self):
        record = {"status": "processing", "updated_at": "2026-11-11T16:05:00+00:00"}
        # 15 minutes idle: past a 14-minute limit, still inside a 16-minute one.
        self.assertTrue(pipeline.is_stalled(record, 840, NOW))
        self.assertFalse(pipeline.is_stalled(record, 960, NOW))

    def test_finished_job_is_never_stalled(self):
        record = {"status": "completed", "updated_at": "2026-11-11T16:05:00+00:00"}
        self.assertFalse(pipeline.is_stalled(record, 60, NOW))

    def test_stall_message_explains_the_gpu_culprit(self):
        with mock.patch("ctypes.WinDLL", side_effect=OSError("nope")):
            message = pipeline.stall_message(900)
        self.assertIn("stalled", message.lower())
        self.assertIn("15 minutes", message)
        self.assertIn("CUDA pack", message)

    def test_stall_message_falls_back_to_the_force_cpu_hint(self):
        with mock.patch.object(pipeline, "cuda_unavailable_reason", return_value=None):
            message = pipeline.stall_message(600)
        self.assertIn("SONICBRIEF_FORCE_CPU", message)


if __name__ == "__main__":
    unittest.main()
