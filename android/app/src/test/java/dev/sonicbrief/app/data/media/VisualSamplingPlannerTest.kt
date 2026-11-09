package dev.sonicbrief.app.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualSamplingPlannerTest {
    @Test
    fun shortVideoUsesFiveSecondCoverage() {
        assertEquals(
            listOf(0L, 5_000L, 10_000L),
            VisualSamplingPlanner.timestamps(15_000L),
        )
    }

    @Test
    fun twoHourVideoNeverExceedsFrameCap() {
        val timestamps = VisualSamplingPlanner.timestamps(2L * 60L * 60L * 1000L)

        assertEquals(VisualSamplingPlanner.MAX_VISUAL_FRAMES, timestamps.size)
        assertEquals(timestamps, timestamps.sorted())
        assertTrue(timestamps.all { it in 0 until 2L * 60L * 60L * 1000L })
    }

    @Test
    fun urlSizedBatchStaysBoundedAndSpansLongMedia() {
        val timestamps = VisualSamplingPlanner.timestamps(398_000L, maxFrames = 12)

        assertEquals(12, timestamps.size)
        assertEquals(0L, timestamps.first())
        assertTrue(timestamps.last() > 300_000L)
    }

    @Test
    fun invalidDurationProducesNoFrames() {
        assertTrue(VisualSamplingPlanner.timestamps(null).isEmpty())
        assertTrue(VisualSamplingPlanner.timestamps(0L).isEmpty())
        assertTrue(VisualSamplingPlanner.timestamps(-1L).isEmpty())
    }

    @Test
    fun customSingleFrameIsSafe() {
        assertEquals(listOf(0L), VisualSamplingPlanner.timestamps(120_000L, maxFrames = 1))
    }
}
