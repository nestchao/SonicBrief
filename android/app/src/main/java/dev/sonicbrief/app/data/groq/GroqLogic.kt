package dev.sonicbrief.app.data.groq

import dev.sonicbrief.app.data.gemini.ParsedSegment
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

const val DEFAULT_GROQ_MODEL = "whisper-large-v3-turbo"
const val GROQ_QUALITY_MODEL = "whisper-large-v3"

/** 25 MB provider cap; uploads stay safely under it so a file near the limit is never rejected. */
const val GROQ_MAX_UPLOAD_BYTES = 20L * 1024L * 1024L

const val GROQ_TARGET_CHUNK_MS = 20 * 60 * 1000L
const val GROQ_CHUNK_OVERLAP_MS = 2_000L

/** Small tolerance for model timestamps that overshoot a chunk boundary by a breath. */
const val GROQ_TIMESTAMP_TOLERANCE_SEC = 5.0

object GroqModels {
    val ALL = listOf(DEFAULT_GROQ_MODEL, GROQ_QUALITY_MODEL)

    fun isValidName(name: String): Boolean = ALL.contains(name.trim())
}

object GroqJson {
    /** Parses an OpenAI-style `verbose_json` transcription response into plain segments. */
    fun parseVerbose(raw: String): GroqTranscription {
        val root = JSONObject(raw.ifBlank { throw IOException("Groq returned an empty response.") })
        val language = root.optString("language", "").trim().ifBlank { "und" }.let(::normalizeLanguage)
        val array: JSONArray = root.optJSONArray("segments") ?: JSONArray()
        val segments = buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val text = item.optString("text", "").trim()
                if (text.isEmpty()) continue
                val start = item.optDouble("start", Double.NaN).takeIf { it.isFinite() } ?: 0.0
                val end = item.optDouble("end", Double.NaN).takeIf { it.isFinite() } ?: start
                add(
                    GroqSegment(
                        startSeconds = max(0.0, start),
                        endSeconds = max(max(0.0, start), end),
                        text = text,
                        avgLogprob = item.optDouble("avg_logprob", Double.NaN).takeIf { it.isFinite() },
                        noSpeechProb = item.optDouble("no_speech_prob", Double.NaN).takeIf { it.isFinite() },
                    ),
                )
            }
        }
        return GroqTranscription(language, segments)
    }

    private fun normalizeLanguage(raw: String): String {
        val key = raw.trim().lowercase()
        if (key.isBlank() || key == "und") return "und"
        if (key.length <= 3 && !key.contains(' ')) return key.substringBefore('-')
        return when (key.substringBefore(' ').substringBefore('(').trim()) {
            "english" -> "en"
            "chinese" -> "zh"
            "mandarin" -> "zh"
            "cantonese", "yue" -> "yue"
            "malay" -> "ms"
            "indonesian" -> "id"
            "japanese" -> "ja"
            "korean" -> "ko"
            "spanish" -> "es"
            "french" -> "fr"
            "german" -> "de"
            "portuguese" -> "pt"
            "russian" -> "ru"
            "arabic" -> "ar"
            "hindi" -> "hi"
            "thai" -> "th"
            "vietnamese" -> "vi"
            "tamil" -> "ta"
            else -> raw.trim()
        }
    }
}

data class GroqChunk(val index: Int, val startMs: Long, val endMs: Long) {
    val lengthMs: Long get() = endMs - startMs
    /** Absolute start of the *new* audio in this window; earlier seconds are overlap with the previous chunk. */
    val effectiveStartMs: Long get() = if (index == 0) startMs else startMs + GROQ_CHUNK_OVERLAP_MS
}

object GroqChunkPlanner {
    /**
     * Deterministic time windows with a small overlap so words are not cut at boundaries.
     * Media of one chunk (or unknown duration) is a single request; the byte-size limit is
     * enforced separately by shrinking windows until every upload fits.
     */
    fun plan(durationMs: Long?, chunkMs: Long = GROQ_TARGET_CHUNK_MS): List<GroqChunk> {
        if (durationMs == null || durationMs <= chunkMs) return emptyList()
        val chunks = mutableListOf<GroqChunk>()
        var start = 0L
        var index = 0
        while (start < durationMs) {
            var end = minOf(start + chunkMs, durationMs)
            // Never emit a trailing sliver: fold a tiny tail into the previous chunk.
            if (durationMs - end in 1 until GROQ_CHUNK_OVERLAP_MS * 2 && chunks.isNotEmpty()) {
                val prev = chunks.removeLast()
                chunks += prev.copy(endMs = durationMs)
                break
            }
            chunks += GroqChunk(index++, start, end)
            if (end >= durationMs) break
            start = end - GROQ_CHUNK_OVERLAP_MS
        }
        return chunks
    }

    /**
     * Drops the overlapped head of follow-up chunks, then shifts onto the global timeline
     * exactly once. Segments fully inside the overlap are duplicates of the previous chunk.
     */
    fun merge(segments: List<ParsedSegment>, chunk: GroqChunk): List<ParsedSegment> {
        val offset = chunk.startMs / 1000.0
        val overlapSec = if (chunk.index == 0) 0.0 else GROQ_CHUNK_OVERLAP_MS / 1000.0
        val limit = chunk.lengthMs / 1000.0
        return segments
            .filter { it.startSeconds >= overlapSec - 0.05 && it.startSeconds <= limit + GROQ_TIMESTAMP_TOLERANCE_SEC }
            .map {
                ParsedSegment(
                    startSeconds = offset + it.startSeconds.coerceAtMost(limit),
                    endSeconds = offset + it.endSeconds.coerceAtMost(limit),
                    text = it.text,
                )
            }
    }

    /**
     * Validates a chunk's segments against the chunk/audio duration. Returns the cleaned list;
     * throws when timestamps are structurally invalid so a corrupt chunk is never silently kept.
     */
    fun validate(
        segments: List<ParsedSegment>,
        chunk: GroqChunk,
        audioDurationMs: Long?,
    ): List<ParsedSegment> {
        val limit = chunk.lengthMs / 1000.0
        segments.forEachIndexed { position, segment ->
            require(segment.startSeconds.isFinite() && segment.endSeconds.isFinite()) {
                "Groq returned a non-numeric timestamp (chunk ${chunk.index + 1}, segment ${position + 1})."
            }
            require(segment.startSeconds >= 0) {
                "Groq returned a negative timestamp (chunk ${chunk.index + 1}, segment ${position + 1})."
            }
            require(segment.endSeconds > segment.startSeconds) {
                "Groq returned an empty or reversed segment (chunk ${chunk.index + 1}, segment ${position + 1})."
            }
            require(segment.startSeconds <= limit + GROQ_TIMESTAMP_TOLERANCE_SEC) {
                "Groq returned a timestamp beyond the audio it was given " +
                    "(chunk ${chunk.index + 1}, segment ${position + 1})."
            }
            if (position > 0) {
                require(segment.startSeconds + 0.001 >= segments[position - 1].startSeconds) {
                    "Groq returned out-of-order segments (chunk ${chunk.index + 1})."
                }
            }
        }
        // Global guard: nothing may claim to start after the real audio ends.
        val audioSec = audioDurationMs?.div(1000.0)
        if (audioSec != null) {
            val offset = chunk.startMs / 1000.0
            segments.forEachIndexed { position, segment ->
                require(offset + segment.startSeconds <= audioSec + GROQ_TIMESTAMP_TOLERANCE_SEC) {
                    "Groq returned a timestamp beyond the audio duration " +
                        "(chunk ${chunk.index + 1}, segment ${position + 1})."
                }
            }
        }
        return segments
    }
}

object GroqErrors {
    /** Actionable, key-safe message for a failed Groq request. Never includes the API key. */
    fun userMessage(failure: Throwable): String {
        val groq = generateSequence(failure) { it.cause }.filterIsInstance<GroqException>().firstOrNull()
        if (groq != null) return forStatus(groq)
        val text = (failure.message.orEmpty()).lowercase()
        return when {
            "cancel" in text -> "Groq transcription was cancelled."
            "unable to resolve host" in text || "failed to connect" in text || "connection " in text ||
                "timeout" in text || "timed out" in text || "network" in text ->
                "Could not reach Groq. Check the connection and try again."
            else -> failure.message?.takeIf { it.isNotBlank() } ?: "Groq transcription failed."
        }
    }

    private fun forStatus(failure: GroqException): String {
        val detail = failure.message.orEmpty().substringAfter(": ").trim().take(220)
        return when (failure.statusCode) {
            401, 403 -> "Groq rejected the API key. Check the key in Settings — it may be wrong, revoked, or out of credit."
            404 -> "Groq could not find the model. Use whisper-large-v3-turbo or whisper-large-v3."
            413 -> "The audio upload was too large for Groq. Try a shorter clip or a smaller file."
            422 -> "Groq could not process this audio (${detail.ifBlank { "unsupported media" }})."
            429 -> "Groq is rate-limiting this key right now. Wait a minute and tap Retry — SonicBrief retries with backoff automatically."
            in 500..599 -> "Groq had a server problem. SonicBrief retries automatically; if it continues, try again later."
            else -> "Groq transcription failed (${detail.ifBlank { failure.message }})"
        }
    }

    fun isKeyProblem(failure: GroqException): Boolean =
        failure.statusCode == 401 || failure.statusCode == 403 ||
            (failure.statusCode == 400 && failure.message.orEmpty().contains("api key", ignoreCase = true)) ||
            failure.statusCode == 404
}
