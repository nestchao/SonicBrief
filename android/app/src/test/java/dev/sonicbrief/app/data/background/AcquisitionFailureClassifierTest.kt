package dev.sonicbrief.app.data.background

import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AcquisitionFailureClassifierTest {
    private val classifier = AcquisitionFailureClassifier()

    @Test
    fun networkFailureIsTransientAndAutomaticallyRetryable() {
        val kind = classifier.classify(IOException("network unreachable"))
        assertEquals(AcquisitionFailureKind.TRANSIENT_NETWORK, kind)
        assertTrue(classifier.retryable(kind))
        assertTrue(classifier.userRetryAllowed(kind))
    }

    @Test
    fun http429IsRateLimitAndAutomaticallyRetryable() {
        val kind = classifier.classify(
            IllegalStateException("ERROR: HTTP Error 429: Too Many Requests"),
        )
        assertEquals(AcquisitionFailureKind.RATE_LIMIT, kind)
        assertTrue(classifier.retryable(kind))
    }

    @Test
    fun youtubeBotChallengeRequiresUserActionInsteadOfAutomaticRetry() {
        val kind = classifier.classify(
            IllegalStateException("YouTube: Sign in to confirm you're not a bot"),
        )
        assertEquals(AcquisitionFailureKind.BOT_OR_AUTH_CHALLENGE, kind)
        assertFalse(classifier.retryable(kind))
        assertTrue(classifier.userRetryAllowed(kind))
        assertTrue(classifier.userMessage(kind, "fallback").contains("cookies.txt"))
    }

    @Test
    fun unsupportedSourceIsTerminal() {
        val kind = classifier.classify(
            IllegalArgumentException("Unsupported URL: custom://video"),
        )
        assertEquals(AcquisitionFailureKind.UNSUPPORTED_SOURCE, kind)
        assertFalse(classifier.retryable(kind))
        assertFalse(classifier.userRetryAllowed(kind))
    }

    @Test
    fun cancellationIsNotRetried() {
        val kind = classifier.classify(CancellationException("cancelled"))
        assertEquals(AcquisitionFailureKind.CANCELLED, kind)
        assertFalse(classifier.retryable(kind))
        assertFalse(classifier.userRetryAllowed(kind))
    }
}
