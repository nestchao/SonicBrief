package dev.sonicbrief.app.domain.model

import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class JobHealingTest {

    @Test
    fun failedJobBecomesCompletedAfterSummary() {
        val failed = Job(
            id = UUID.randomUUID(),
            sourceType = SourceType.URL,
            status = JobStatus.FAILED,
            stage = JobStage.FAILED,
            progress = 85,
            errorCode = "processing_failure",
            errorMessage = "Gemini returned no text.",
        )
        val healed = failed.healedAfterSummary(Instant.parse("2026-11-10T10:00:00Z"))

        assertEquals(JobStatus.COMPLETED, healed.status)
        assertEquals(JobStage.COMPLETED, healed.stage)
        assertEquals(100, healed.progress)
        assertNull(healed.stageDetail)
        assertNull(healed.errorCode)
        assertNull(healed.errorMessage)
        assertEquals(Instant.parse("2026-11-10T10:00:00Z"), healed.completedAt)
        // Everything else (transcript reference, model, ...) is untouched.
        assertEquals(failed.id, healed.id)
        assertEquals(failed.model, healed.model)
    }

    @Test
    fun cancelledJobHealsToo() {
        val cancelled = Job(
            id = UUID.randomUUID(),
            sourceType = SourceType.UPLOAD,
            status = JobStatus.CANCELLED,
        )
        assertEquals(JobStatus.COMPLETED, cancelled.healedAfterSummary().status)
    }

    @Test
    fun completedAndActiveJobsPassThroughUntouched() {
        val completed = Job(
            id = UUID.randomUUID(),
            sourceType = SourceType.UPLOAD,
            status = JobStatus.COMPLETED,
        )
        assertSame(completed, completed.healedAfterSummary())

        val active = Job(
            id = UUID.randomUUID(),
            sourceType = SourceType.URL,
            status = JobStatus.ACTIVE,
        )
        assertSame(active, active.healedAfterSummary())
    }
}
