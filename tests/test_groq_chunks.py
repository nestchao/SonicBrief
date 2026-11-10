"""Run with: .venv\\Scripts\\python.exe -m unittest tests.test_groq_chunks"""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "backend"))

import pipeline  # noqa: E402


def seg(start: float, end: float, text: str = "speech") -> dict:
    return {"start": start, "end": end, "text": text, "speaker": None}


class PlanTests(unittest.TestCase):
    def test_short_media_is_a_single_request(self):
        self.assertEqual(pipeline.plan_groq_chunks(None), [])
        self.assertEqual(pipeline.plan_groq_chunks(10 * 60), [])
        self.assertEqual(pipeline.plan_groq_chunks(20 * 60), [])

    def test_long_media_plans_overlapping_windows(self):
        chunks = pipeline.plan_groq_chunks(45 * 60)
        self.assertEqual(len(chunks), 3)
        self.assertEqual(chunks[0][0], 0.0)
        self.assertAlmostEqual(chunks[1][0], chunks[0][1] - pipeline.GROQ_CHUNK_OVERLAP_SECONDS)
        self.assertEqual(chunks[-1][1], 45 * 60)


class MergeTests(unittest.TestCase):
    def test_offset_applied_once_and_overlap_deduped(self):
        merged = pipeline.merge_groq_chunks(
            [seg(0.5, 1.5, "duplicate"), seg(3.0, 7.0, "new")],
            chunk_start=1198.0,
            chunk_length=1200.0,
            first=False,
        )
        self.assertEqual(len(merged), 1)
        self.assertAlmostEqual(merged[0]["start"], 1201.0)
        self.assertEqual(merged[0]["text"], "new")

    def test_first_chunk_keeps_head(self):
        merged = pipeline.merge_groq_chunks([seg(0.5, 1.5, "intro")], 0.0, 1200.0, first=True)
        self.assertEqual(len(merged), 1)
        self.assertAlmostEqual(merged[0]["start"], 0.5)


class ValidationTests(unittest.TestCase):
    def test_rejects_timestamps_beyond_audio_duration(self):
        with self.assertRaises(ValueError) as ctx:
            pipeline.validate_groq_segments(
                [seg(3360.0, 3365.0, "fifty-six minutes into an eleven-minute clip")],
                chunk_start=0.0,
                chunk_length=660.0,
                audio_duration=660.0,
            )
        self.assertIn("beyond the audio", str(ctx.exception))

    def test_rejects_empty_and_reversed_segments(self):
        with self.assertRaises(ValueError):
            pipeline.validate_groq_segments([seg(5.0, 5.0)], 0.0, 600.0, 600.0)
        with self.assertRaises(ValueError):
            pipeline.validate_groq_segments([seg(9.0, 4.0)], 0.0, 600.0, 600.0)

    def test_accepts_valid_segments(self):
        valid = pipeline.validate_groq_segments([seg(0.0, 4.2), seg(4.2, 8.0)], 0.0, 600.0, 600.0)
        self.assertEqual(len(valid), 2)


class ErrorTests(unittest.TestCase):
    def test_actionable_messages(self):
        self.assertIn("API key", pipeline._groq_error_message(401, '{"error": {"message": "invalid", "code": "invalid_api_key"}}'))
        self.assertIn("rate-limiting", pipeline._groq_error_message(429, "slow down"))
        self.assertIn("too large", pipeline._groq_error_message(413, "{}"))


if __name__ == "__main__":
    unittest.main()
