package dev.sonicbrief.app.data.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleRuntimeFailureTest {
    @Test
    fun detectsNinetyDayWarning() {
        val failure = IllegalStateException(
            "WARNING: Your yt-dlp version (2025.11.12) is older than 90 days! " +
                "It is strongly recommended to always use the latest version.",
        )
        assertTrue(isStaleRuntimeFailure(failure))
    }

    @Test
    fun detectsWrappedWarningInCauseChain() {
        val root = IllegalStateException("Your yt-dlp version is older than 90 days")
        val wrapped = RuntimeException("download failed", root)
        assertTrue(isStaleRuntimeFailure(wrapped))
    }

    @Test
    fun ignoresUnrelatedFailures() {
        assertFalse(isStaleRuntimeFailure(IllegalStateException("ERROR: HTTP Error 403: Forbidden")))
        assertFalse(isStaleRuntimeFailure(IllegalStateException("Sign in to confirm you're not a bot")))
        assertFalse(isStaleRuntimeFailure(IllegalStateException("network unreachable")))
    }
}
