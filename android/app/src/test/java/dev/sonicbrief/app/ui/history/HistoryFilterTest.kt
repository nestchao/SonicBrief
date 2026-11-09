package dev.sonicbrief.app.ui.history

import dev.sonicbrief.app.domain.model.Job
import dev.sonicbrief.app.domain.model.SourceType
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryFilterTest {
    private fun job(title: String?, creator: String? = null, url: String? = null) =
        Job(UUID.randomUUID(), SourceType.URL, sourceUrl = url, title = title, creator = creator)

    @Test
    fun matchesTitleCreatorAndUrlCaseInsensitively() {
        val jobs = listOf(job("Kotlin Talk", "Alice"), job("Cooking", "Bob", "https://youtu.be/xyz"))
        assertEquals(1, filterJobs(jobs, "kotlin").size)
        assertEquals(1, filterJobs(jobs, "BOB").size)
        assertEquals(1, filterJobs(jobs, "youtu.be").size)
        assertEquals(2, filterJobs(jobs, "  ").size)
        assertEquals(0, filterJobs(jobs, "zzz").size)
    }

    @Test
    fun displayTitleFallsBackToFilenameThenUrl() {
        val withUrl = job(null, url = "https://x.test/a")
        assertEquals("https://x.test/a", withUrl.displayTitle)
        assertEquals("a.mp3", withUrl.copy(sourceFilename = "a.mp3").displayTitle)
        assertEquals("T", withUrl.copy(title = "T").displayTitle)
    }
}
