package dev.sonicbrief.app.data.groq

import dev.sonicbrief.app.data.gemini.ParsedSegment
import dev.sonicbrief.app.data.gemini.TranscriptionResult
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

@Singleton
class GroqTranscriber @Inject constructor(
    private val api: GroqApi,
    private val preparer: GroqAudioPreparer,
) {
    /**
     * Uploads each prepared chunk to Groq in order and merges the results onto one global
     * timeline. Completed chunks are never re-uploaded: only a failed chunk is retried, and a
     * chunk that keeps failing fails the job explicitly instead of returning a partial result.
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
        require(GroqModels.isValidName(model)) { "Unknown Groq model: $model" }
        onStage(Stage.PREPARING, 0)
        val chunks = preparer.prepare(file, displayName, durationMs)
        try {
            if (chunks.size == 1 && !chunks[0].temporary) {
                onStage(Stage.UPLOADING, 0)
                val parsed = requestChunk(apiKey, model, chunks[0], languageHint)
                onStage(Stage.TRANSCRIBING, 100)
                val validated = GroqChunkPlanner.validate(
                    parsed.segments.map { ParsedSegment(it.startSeconds, it.endSeconds, it.text) },
                    chunks[0].chunk,
                    durationMs,
                )
                if (validated.isEmpty()) throw IOException("Groq returned an empty transcript.")
                return TranscriptionResult(parsed.language, validated)
            }

            val merged = mutableListOf<ParsedSegment>()
            var language = "und"
            chunks.forEachIndexed { position, slice ->
                onStage(Stage.UPLOADING, position * 100 / chunks.size)
                val parsed = requestChunk(apiKey, model, slice, languageHint)
                if (language == "und") language = parsed.language
                val validated = GroqChunkPlanner.validate(
                    parsed.segments.map { ParsedSegment(it.startSeconds, it.endSeconds, it.text) },
                    slice.chunk,
                    durationMs,
                )
                merged += GroqChunkPlanner.merge(validated, slice.chunk)
                onStage(Stage.TRANSCRIBING, (position + 1) * 100 / chunks.size)
            }
            if (merged.isEmpty()) throw IOException("Groq returned an empty transcript.")
            return TranscriptionResult(language, merged.sortedBy { it.startSeconds })
        } finally {
            preparer.discard(chunks)
        }
    }

    private suspend fun requestChunk(
        apiKey: String,
        model: String,
        slice: GroqChunkFile,
        languageHint: String,
    ): GroqTranscription {
        var lastFailure: Throwable? = null
        // Transport-level retries already happen inside GroqApi; this loop only gives a chunk
        // one extra chance before the job fails with an explicit chunk reference.
        repeat(MAX_CHUNK_ATTEMPTS) {
            try {
                val result = api.transcribeVerbose(apiKey, slice.file, slice.mimeType, model, languageHint)
                if (result.segments.isNotEmpty()) return result
                lastFailure = IOException("Groq returned an empty transcript for this section.")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: GroqException) {
                throw failure
            } catch (failure: IOException) {
                lastFailure = failure
            }
        }
        val range = "section ${slice.chunk.index + 1} (${formatRange(slice.chunk)})"
        throw IOException("Groq transcription failed for $range: ${lastFailure?.message}", lastFailure)
    }

    private fun formatRange(chunk: GroqChunk): String {
        fun clock(ms: Long): String {
            val total = ms / 1000
            return "%02d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
        }
        return "${clock(chunk.startMs)}–${clock(chunk.endMs)}"
    }

    enum class Stage { PREPARING, UPLOADING, TRANSCRIBING }

    private companion object {
        const val MAX_CHUNK_ATTEMPTS = 2
    }
}
