package dev.sonicbrief.app.data.background

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

@Singleton
class AcquisitionFailureClassifier @Inject constructor() {
    fun classify(throwable: Throwable): AcquisitionFailureKind {
        if (throwable is CancellationException) return AcquisitionFailureKind.CANCELLED
        if (throwable is UnknownHostException || throwable is SocketTimeoutException || throwable is IOException) {
            return AcquisitionFailureKind.TRANSIENT_NETWORK
        }

        val message = flattenMessages(throwable).lowercase()
        return when {
            message.contains("older than 90 days") ||
                message.contains("your yt-dlp version") ||
                message.contains("downloader needs an update") ->
                AcquisitionFailureKind.STALE_RUNTIME

            message.contains("sign in to confirm you're not a bot") ||
                message.contains("sign in to confirm you’re not a bot") ||
                message.contains("confirm you're not a bot") ||
                message.contains("confirm you’re not a bot") ||
                message.contains("authentication required") && message.contains("youtube") ->
                AcquisitionFailureKind.BOT_OR_AUTH_CHALLENGE

            message.contains("http 429") ||
                message.contains("http error 429") ||
                message.contains("429: too many requests") ||
                message.contains("too many requests") ||
                message.contains("rate limit") ||
                message.contains("rate_limit") ||
                message.contains("rate-limit") ->
                AcquisitionFailureKind.RATE_LIMIT

            message.contains("network unreachable") ||
                message.contains("connection reset") ||
                message.contains("connection refused") ||
                message.contains("connection timeout") ||
                message.contains("timed out") ||
                message.contains("temporary failure") ||
                message.contains("temporarily unavailable") ->
                AcquisitionFailureKind.TRANSIENT_NETWORK

            message.contains("unsupported url") ||
                message.contains("unsupported source") ||
                message.contains("no suitable extractor") ||
                message.contains("is not a valid public") ->
                AcquisitionFailureKind.UNSUPPORTED_SOURCE

            message.contains("sign in before") ||
                message.contains("authentication failed") ||
                message.contains("session") && message.contains("expired") ->
                AcquisitionFailureKind.AUTH_SESSION_REQUIRED

            message.contains("gemini") ||
                message.contains("groq") ||
                message.contains("whisper") ||
                message.contains("processing failed") ||
                message.contains("transcription") ||
                message.contains("summar") ->
                AcquisitionFailureKind.PROCESSING_FAILURE

            else -> AcquisitionFailureKind.UNKNOWN
        }
    }

    fun retryable(kind: AcquisitionFailureKind): Boolean =
        kind == AcquisitionFailureKind.TRANSIENT_NETWORK ||
            kind == AcquisitionFailureKind.RATE_LIMIT ||
            kind == AcquisitionFailureKind.STALE_RUNTIME

    fun userRetryAllowed(kind: AcquisitionFailureKind): Boolean = when (kind) {
        AcquisitionFailureKind.UNSUPPORTED_SOURCE,
        AcquisitionFailureKind.AUTH_SESSION_REQUIRED,
        AcquisitionFailureKind.CANCELLED,
        -> false

        else -> true
    }

    fun userMessage(kind: AcquisitionFailureKind, fallback: String): String = when (kind) {
        AcquisitionFailureKind.BOT_OR_AUTH_CHALLENGE ->
            "The platform asked for sign-in or bot verification on this device. Try again later, or import a cookies.txt from a browser where you are signed in (Settings → Cookies), or use a local file instead."
        AcquisitionFailureKind.RATE_LIMIT ->
            "The provider (the media site or the transcription service) is rate-limiting requests. SonicBrief retries with backoff; if it continues, try again later."
        AcquisitionFailureKind.TRANSIENT_NETWORK ->
            "Network access was interrupted. SonicBrief can retry when connectivity is available."
        AcquisitionFailureKind.UNSUPPORTED_SOURCE ->
            "This public media source is not supported by the current acquisition runtime."
        AcquisitionFailureKind.AUTH_SESSION_REQUIRED ->
            "The saved cookies are no longer valid. Re-import a fresh cookies.txt in Settings or remove it."
        AcquisitionFailureKind.STALE_RUNTIME ->
            "The built-in downloader fell behind the site's changes. Make sure the phone is online and tap Retry — SonicBrief refreshes its downloader automatically."
        AcquisitionFailureKind.CANCELLED ->
            "URL processing was cancelled."
        else -> fallback
    }

    private fun flattenMessages(throwable: Throwable): String = buildString {
        var current: Throwable? = throwable
        var depth = 0
        while (current != null && depth < 8) {
            if (isNotEmpty()) append(" | ")
            append(current.message.orEmpty())
            current = current.cause
            depth += 1
        }
    }
}
