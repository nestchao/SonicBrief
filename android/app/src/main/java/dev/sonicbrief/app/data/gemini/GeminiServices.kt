package dev.sonicbrief.app.data.gemini

import dev.sonicbrief.app.domain.model.Transcript
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONException

data class TranscriptionResult(val language: String, val segments: List<ParsedSegment>)

private const val MAX_CHUNK_ATTEMPTS = 3

@Singleton
class GeminiTranscriber @Inject constructor(
    private val api: GeminiApi,
) {
    /**
     * Uploads the audio once, then transcribes it in a single request (short media) or one request per
     * 20-minute range against the same uploaded file (long media), merging onto one global timeline.
     */
    suspend fun transcribe(
        apiKey: String,
        model: String,
        file: File,
        mimeType: String,
        displayName: String,
        durationMs: Long?,
        languageHint: String,
        onStage: suspend (stage: Stage, percent: Int) -> Unit = { _, _ -> },
    ): TranscriptionResult {
        onStage(Stage.UPLOADING, 0)
        val uploaded = api.uploadFile(apiKey, file, mimeType, displayName)
        try {
            val ranges = LongMediaPlanner.plan(durationMs)
            if (ranges.isEmpty()) {
                onStage(Stage.TRANSCRIBING, 0)
                val parsed = requestTranscript(apiKey, model, uploaded, languageHint, null)
                onStage(Stage.TRANSCRIBING, 100)
                return TranscriptionResult(parsed.language, parsed.segments)
            }

            val merged = mutableListOf<ParsedSegment>()
            var language = "und"
            ranges.forEachIndexed { position, range ->
                onStage(Stage.TRANSCRIBING, position * 100 / ranges.size)
                val parsed = requestTranscript(apiKey, model, uploaded, languageHint, range)
                if (language == "und") language = parsed.language
                merged += LongMediaPlanner.mergeOffset(LongMediaPlanner.normalizeChunk(parsed.segments, range), range)
            }
            onStage(Stage.TRANSCRIBING, 100)
            return TranscriptionResult(language, merged.sortedBy { it.startSeconds })
        } finally {
            withContext(NonCancellable) { api.deleteFile(apiKey, uploaded) }
        }
    }

    private suspend fun requestTranscript(
        apiKey: String,
        model: String,
        file: UploadedFile,
        languageHint: String,
        range: ChunkRange?,
    ): ParsedTranscript {
        var lastFailure: Throwable? = null
        repeat(MAX_CHUNK_ATTEMPTS) {
            try {
                val raw = api.generate(
                    apiKey = apiKey,
                    model = model,
                    file = file,
                    prompt = GeminiPrompts.transcriptionPrompt(languageHint, range),
                    responseSchema = transcriptResponseSchema(),
                )
                val parsed = GeminiJson.parseTranscript(raw)
                if (parsed.segments.isNotEmpty()) return parsed
                lastFailure = IOException("Gemini returned an empty transcript.")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: JSONException) {
                lastFailure = IOException("Gemini returned malformed transcript JSON.", failure)
            } catch (failure: GeminiException) {
                throw failure
            }
        }
        throw IOException(
            "Gemini transcription failed after $MAX_CHUNK_ATTEMPTS attempts" +
                (range?.let { " (chunk ${it.index + 1})" } ?: "") + ": ${lastFailure?.message}",
            lastFailure,
        )
    }

    enum class Stage { UPLOADING, TRANSCRIBING }
}

@Singleton
class GeminiSummarizer @Inject constructor(
    private val api: GeminiApi,
) {
    suspend fun summarize(
        apiKey: String,
        model: String,
        transcript: Transcript,
        languageCode: String,
        instructions: String?,
    ): String {
        val prompt = GeminiPrompts.summaryPrompt(
            transcriptText = GeminiPrompts.formatTranscriptForSummary(transcript),
            languageCode = languageCode,
            storedInstructions = instructions,
        )
        val text = api.generate(apiKey = apiKey, model = model, file = null, prompt = prompt)
        return text.trim().removeSurrounding("```markdown", "```").removeSurrounding("```", "```").trim()
    }
}
