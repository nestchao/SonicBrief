package dev.sonicbrief.app.data.groq

import dev.sonicbrief.app.data.gemini.ParsedSegment
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroqLogicTest {

    @Test
    fun modelsOnlyAllowTheTwoWhisperOptions() {
        assertTrue(GroqModels.isValidName("whisper-large-v3-turbo"))
        assertTrue(GroqModels.isValidName("whisper-large-v3"))
        assertTrue(GroqModels.isValidName("  whisper-large-v3-turbo  "))
        assertFalse(GroqModels.isValidName("whisper-large-v3-turbo-2"))
        assertFalse(GroqModels.isValidName("gemini-flash-lite-latest"))
        assertFalse(GroqModels.isValidName(""))
        assertEquals(DEFAULT_GROQ_MODEL, "whisper-large-v3-turbo")
    }

    @Test
    fun verboseJsonMapsToSegmentsAndNormalizesLanguage() {
        val payload = JSONObject()
            .put("language", "english")
            .put(
                "segments",
                org.json.JSONArray()
                    .put(JSONObject().put("start", 0.0).put("end", 4.2).put("text", "hello world"))
                    .put(JSONObject().put("start", 4.2).put("end", 8.0).put("text", "  "))
                    .put(JSONObject().put("start", 8.0).put("end", 9.5).put("text", "second line")),
            ).toString()
        val parsed = GroqJson.parseVerbose(payload)
        assertEquals("en", parsed.language)
        assertEquals(2, parsed.segments.size)
        assertEquals(0.0, parsed.segments[0].startSeconds, 0.0)
        assertEquals(4.2, parsed.segments[0].endSeconds, 0.0)
        assertEquals("hello world", parsed.segments[0].text)
    }

    @Test
    fun shortMediaIsASingleRequest() {
        assertTrue(GroqChunkPlanner.plan(10 * 60 * 1000L).isEmpty())
        assertTrue(GroqChunkPlanner.plan(null).isEmpty())
        assertTrue(GroqChunkPlanner.plan(GROQ_TARGET_CHUNK_MS).isEmpty())
    }

    @Test
    fun longMediaPlansOverlappingWindows() {
        val chunks = GroqChunkPlanner.plan(45 * 60 * 1000L)
        assertEquals(3, chunks.size)
        assertEquals(0L, chunks[0].startMs)
        assertEquals(chunks[0].endMs - GROQ_CHUNK_OVERLAP_MS, chunks[1].startMs)
        assertEquals(45 * 60 * 1000L, chunks[2].endMs)
    }

    @Test
    fun mergeAppliesOffsetOnceAndDropsOverlapDuplicates() {
        val chunk = GroqChunk(index = 1, startMs = 1_198_000L, endMs = 2_398_000L)
        val merged = GroqChunkPlanner.merge(
            listOf(
                ParsedSegment(0.5, 1.5, "overlap duplicate"),
                ParsedSegment(3.0, 7.0, "new speech"),
            ),
            chunk,
        )
        assertEquals(1, merged.size)
        assertEquals(1_198_000L / 1000.0 + 3.0, merged[0].startSeconds, 0.001)
        assertEquals("new speech", merged[0].text)
    }

    @Test
    fun mergeKeepsFirstChunkHead() {
        val chunk = GroqChunk(index = 0, startMs = 0L, endMs = 1_200_000L)
        val merged = GroqChunkPlanner.merge(listOf(ParsedSegment(0.5, 1.5, "intro")), chunk)
        assertEquals(1, merged.size)
        assertEquals(0.5, merged[0].startSeconds, 0.001)
    }

    @Test
    fun validationRejectsTimestampsBeyondAudioDuration() {
        // A 56-minute timestamp for an 11-minute clip never reaches the global guard:
        // it is rejected as beyond the chunk it was returned for.
        val chunk = GroqChunk(index = 0, startMs = 0L, endMs = 660_000L)
        try {
            GroqChunkPlanner.validate(
                listOf(ParsedSegment(3_360.0, 3_365.0, "fifty-six minutes into an eleven-minute clip")),
                chunk,
                audioDurationMs = 660_000L,
            )
            throw AssertionError("expected an 11-minute guard to reject a 56-minute timestamp")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("beyond the audio"))
        }
        // Inside the chunk window but past the real audio end: the global guard reports it.
        val wideChunk = GroqChunk(index = 0, startMs = 0L, endMs = 700_000L)
        try {
            GroqChunkPlanner.validate(
                listOf(ParsedSegment(670.0, 675.0, "past the real ending")),
                wideChunk,
                audioDurationMs = 660_000L,
            )
            throw AssertionError("expected the global duration guard to fire")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("beyond the audio duration"))
        }
    }

    @Test
    fun validationRejectsEmptyAndReversedSegments() {
        val chunk = GroqChunk(index = 0, startMs = 0L, endMs = 600_000L)
        try {
            GroqChunkPlanner.validate(listOf(ParsedSegment(5.0, 5.0, "empty")), chunk, 600_000L)
            throw AssertionError("expected empty segment to be rejected")
        } catch (_: IllegalArgumentException) { }
        try {
            GroqChunkPlanner.validate(listOf(ParsedSegment(9.0, 4.0, "reversed")), chunk, 600_000L)
            throw AssertionError("expected reversed segment to be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun errorMessagesAreActionableAndKeySafe() {
        assertTrue(GroqErrors.userMessage(GroqException(401, "invalid_api_key", "Groq HTTP 401: bad")).contains("API key"))
        assertTrue(GroqErrors.userMessage(GroqException(429, "rate_limit_exceeded", "Groq HTTP 429: slow")).contains("rate-limiting"))
        assertTrue(GroqErrors.userMessage(GroqException(413, "too large", "Groq HTTP 413")).contains("too large"))
        val keyProblem = GroqException(401, null, "Groq HTTP 401")
        assertTrue(GroqErrors.isKeyProblem(keyProblem))
        assertFalse(GroqErrors.isKeyProblem(GroqException(500, null, "Groq HTTP 500")))
        assertFalse(GroqErrors.userMessage(GroqException(401, null, "Groq HTTP 401: bad")).contains("gsk_"))
    }
}
