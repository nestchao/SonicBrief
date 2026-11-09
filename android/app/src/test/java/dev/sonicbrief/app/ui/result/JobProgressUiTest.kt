package dev.sonicbrief.app.ui.result

import dev.sonicbrief.app.domain.model.JobStage
import org.junit.Assert.assertEquals
import org.junit.Test

class JobProgressUiTest {
    @Test
    fun mapsStagesToStepsInOrder() {
        assertEquals(-1, stepIndexFor(JobStage.CREATED))
        assertEquals(0, stepIndexFor(JobStage.VALIDATING))
        assertEquals(1, stepIndexFor(JobStage.ACQUIRING_MEDIA))
        assertEquals(2, stepIndexFor(JobStage.UPLOADING))
        assertEquals(3, stepIndexFor(JobStage.TRANSCRIBING))
        assertEquals(4, stepIndexFor(JobStage.SUMMARIZING))
    }

    @Test
    fun formatsElapsedClock() {
        assertEquals("0:00", formatElapsed(0L))
        assertEquals("0:07", formatElapsed(7_400L))
        assertEquals("3:42", formatElapsed(222_000L))
        assertEquals("1:05:09", formatElapsed(3_909_000L))
        assertEquals("0:00", formatElapsed(-500L))
    }
}
