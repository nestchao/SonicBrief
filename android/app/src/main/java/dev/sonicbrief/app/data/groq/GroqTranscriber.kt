package dev.sonicbrief.app.data.groq

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaMetadataRetriever
import dev.sonicbrief.app.data.gemini.ChunkRange
import dev.sonicbrief.app.data.gemini.ParsedSegment
import dev.sonicbrief.app.data.gemini.LONG_MEDIA_CHUNK_MS
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class GroqTranscriptionResult(val language: String, val segments: List<ParsedSegment>)

/**
 * Creates bounded upload chunks and persists each successful response in the job cache.
 * WAV chunks are cut on PCM frame boundaries; AAC/M4A chunks are remuxed without re-encoding.
 */
@Singleton
class GroqTranscriber @Inject constructor(
    private val api: GroqApi,
) {
    suspend fun transcribe(
        apiKey: String,
        model: String,
        file: File,
        mimeType: String,
        displayName: String,
        durationMs: Long?,
        languageHint: String,
        cacheDirectory: File,
        onProgress: suspend (Int, Int) -> Unit = { _, _ -> },
    ): GroqTranscriptionResult = withContext(Dispatchers.IO) {
        require(file.isFile && file.length() > 0L) { "The audio file is missing or empty." }
        val cache = File(cacheDirectory, "groq-transcription").apply { mkdirs() }
        val fingerprint = fingerprint(file, model, languageHint)
        val chunks = makeChunks(file, mimeType, durationMs, cache)
        val merged = mutableListOf<ParsedSegment>()
        var language = "und"
        try {
            chunks.forEachIndexed { index, chunk ->
                val resultFile = File(cache, "$fingerprint-${chunk.range.index}.json")
                val response = if (resultFile.isFile) {
                    runCatching { JSONObject(resultFile.readText()) }.getOrNull()
                } else null
                val json = response ?: api.transcribe(
                    apiKey = apiKey,
                    model = model,
                    file = chunk.file,
                    mimeType = chunk.mimeType,
                    displayName = chunk.displayName,
                    languageHint = languageHint,
                ).also { resultFile.writeText(it.toString()) }

                val parsed = parseResponse(json, chunk.durationMs)
                if (language == "und") language = parsed.first
                merged += parsed.second.map { segment ->
                    ParsedSegment(
                        startSeconds = chunk.range.startMs / 1000.0 + segment.startSeconds,
                        endSeconds = chunk.range.startMs / 1000.0 + segment.endSeconds,
                        text = segment.text,
                    )
                }
                onProgress(index + 1, chunks.size)
            }
            val deduplicated = deduplicate(merged.sortedBy { it.startSeconds })
            GroqTranscriptionResult(language, deduplicated)
        } finally {
            chunks.filter { it.temporary }.forEach { it.file.delete() }
        }
    }

    private data class AudioChunk(
        val file: File,
        val mimeType: String,
        val displayName: String,
        val durationMs: Long,
        val range: ChunkRange,
        val temporary: Boolean,
    )

    private fun makeChunks(file: File, mimeType: String, durationMs: Long?, cache: File): List<AudioChunk> {
        if (file.length() <= MAX_BYTES) {
            val duration = wavDuration(file) ?: mediaDuration(file) ?: durationMs
                ?: error("Unable to determine the actual audio duration.")
            return listOf(AudioChunk(file, mimeType, file.name, duration, ChunkRange(0, 0, duration), false))
        }

        val wav = readWavHeader(file)
        if (wav != null) return splitWav(file, wav, cache)

        if (mimeType == "audio/mp4" || file.extension.equals("m4a", true) || file.extension.equals("mp4", true)) {
            val remuxed = splitAac(file, cache)
            if (remuxed.isNotEmpty()) return remuxed
        }
        error(
            "This audio is larger than Groq's 25 MB upload limit and cannot be safely chunked in its current format. " +
                "Try selecting a WAV or M4A/AAC file, or use Gemini for this file."
        )
    }

    private data class WavHeader(
        val dataOffset: Long,
        val dataBytes: Long,
        val byteRate: Long,
        val blockAlign: Int,
        val sampleRate: Int,
        val audioFormat: Int,
        val channels: Int,
        val bitsPerSample: Int,
    )

    private fun readWavHeader(file: File): WavHeader? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.readAscii(4) != "RIFF") return null
            raf.skipBytes(4)
            if (raf.readAscii(4) != "WAVE") return null
            var byteRate = 0L
            var blockAlign = 0
            var sampleRate = 0
            var audioFormat = 0
            var channels = 0
            var bitsPerSample = 0
            while (raf.filePointer + 8 <= raf.length()) {
                val id = raf.readAscii(4)
                val size = Integer.toUnsignedLong(java.lang.Integer.reverseBytes(raf.readInt()))
                val start = raf.filePointer
                when (id) {
                    "fmt " -> {
                        audioFormat = raf.readUnsignedShortLE()
                        channels = raf.readUnsignedShortLE()
                        sampleRate = raf.readIntLE()
                        byteRate = Integer.toUnsignedLong(raf.readIntLE())
                        blockAlign = raf.readUnsignedShortLE()
                        bitsPerSample = raf.readUnsignedShortLE()
                    }
                    "data" -> return WavHeader(start, minOf(size, raf.length() - start), byteRate, blockAlign, sampleRate, audioFormat, channels, bitsPerSample)
                }
                raf.seek(start + size + (size and 1L))
            }
            null
        }
    }.getOrNull()?.takeIf { it.byteRate > 0 && it.blockAlign > 0 && it.dataBytes > 0 && it.audioFormat == 1 && it.bitsPerSample == 16 }

    private fun splitWav(file: File, header: WavHeader, cache: File): List<AudioChunk> {
        val safeChunkBytes = MAX_BYTES - WAV_HEADER_BYTES
        val targetBytes = minOf(safeChunkBytes, header.byteRate * CHUNK_MS / 1000L)
        val alignedBytes = (targetBytes / header.blockAlign) * header.blockAlign
        require(alignedBytes > 0) { "Unable to split this WAV audio into safe Groq upload chunks." }
        val totalDuration = header.dataBytes * 1000L / header.byteRate
        val result = mutableListOf<AudioChunk>()
        var sourceOffset = 0L
        var index = 0
        while (sourceOffset < header.dataBytes) {
            val bytes = minOf(alignedBytes, header.dataBytes - sourceOffset)
            val startMs = sourceOffset * 1000L / header.byteRate
            val endMs = (sourceOffset + bytes) * 1000L / header.byteRate
            val output = File(cache, "chunk-${index}.wav")
            RandomAccessFile(file, "r").use { input ->
                RandomAccessFile(output, "rw").use { out ->
                    out.setLength(0)
                    writeWavHeader(out, header, bytes)
                    input.seek(header.dataOffset + sourceOffset)
                    val buffer = ByteArray(64 * 1024)
                    var remaining = bytes
                    while (remaining > 0) {
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (count < 0) error("Unexpected end of WAV audio while creating chunk ${index + 1}.")
                        out.write(buffer, 0, count)
                        remaining -= count
                    }
                }
            }
            result += AudioChunk(
                output, "audio/wav", output.name, endMs - startMs,
                ChunkRange(index, startMs, endMs), true,
            )
            val overlapBytes = minOf((header.byteRate / 1000L) * OVERLAP_MS, bytes / 4)
            sourceOffset += (bytes - overlapBytes).coerceAtLeast(header.blockAlign.toLong())
            index++
        }
        require(result.isNotEmpty() && result.last().range.endMs >= totalDuration - 1000L) {
            "WAV chunking did not cover the complete audio duration."
        }
        return result
    }

    private fun splitAac(file: File, cache: File): List<AudioChunk> {
        val extractor = MediaExtractor()
        val result = mutableListOf<AudioChunk>()
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm"
            } ?: return emptyList()
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)
            val durationMs = durationUs / 1000L
            require(durationMs > 0L) { "The M4A audio duration is invalid." }
            val estimatedBytesPerMs = file.length().toDouble() / durationMs.toDouble()
            val sizeBoundMs = ((MAX_BYTES * 0.80) / estimatedBytesPerMs).toLong().coerceAtLeast(60_000L)
            val targetChunkMs = minOf(CHUNK_MS, sizeBoundMs)
            val ranges = (0 until ((durationMs + targetChunkMs - 1) / targetChunkMs).toInt()).map { index ->
                val start = index * targetChunkMs
                ChunkRange(index, start, minOf(start + targetChunkMs, durationMs))
            }
            ranges.forEach { range ->
                val output = File(cache, "chunk-${range.index}.m4a")
                val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                var actualStartUs = range.startMs * 1000L
                try {
                    val outTrack = muxer.addTrack(format)
                    muxer.start()
                    extractor.seekTo(range.startMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    actualStartUs = extractor.sampleTime.takeIf { it >= 0L } ?: range.startMs * 1000L
                    val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
                    val info = android.media.MediaCodec.BufferInfo()
                    while (true) {
                        val sampleTime = extractor.sampleTime
                        if (sampleTime < 0L || sampleTime >= range.endMs * 1000L) break
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size <= 0) break
                        info.set(0, size, (sampleTime - actualStartUs).coerceAtLeast(0L), extractor.sampleFlags)
                        muxer.writeSampleData(outTrack, buffer, info)
                        extractor.advance()
                    }
                    muxer.stop()
                } finally {
                    runCatching { muxer.release() }
                }
                require(output.isFile && output.length() in 1..MAX_BYTES) {
                    "The M4A chunk exceeds Groq's upload limit; re-encode the audio at a lower bitrate."
                }
                val actualStartMs = (actualStartUs / 1000L).coerceAtMost(range.startMs)
                val adjustedRange = range.copy(startMs = actualStartMs)
                result += AudioChunk(output, "audio/mp4", output.name, range.endMs - actualStartMs, adjustedRange, true)
            }
            require(result.size == ranges.size && result.lastOrNull()?.range?.endMs ?: 0L >= durationMs - 1_000L) {
                "M4A chunking did not cover the complete audio duration."
            }
        } catch (failure: Throwable) {
            result.forEach { it.file.delete() }
            throw failure
        } finally {
            extractor.release()
        }
        return result
    }

    private fun parseResponse(root: JSONObject, chunkDurationMs: Long): Pair<String, List<ParsedSegment>> {
        val language = root.optString("language").ifBlank { "und" }
        val array = root.optJSONArray("segments") ?: error("Groq returned no segment timestamps. Check response_format and timestamp_granularities.")
        val duration = chunkDurationMs / 1000.0
        val parsed = buildList {
            var previousStart = -1.0
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val start = item.optDouble("start", Double.NaN)
                val end = item.optDouble("end", Double.NaN)
                val text = item.optString("text").trim()
                require(start.isFinite() && end.isFinite() && start >= 0.0 && end > start) {
                    "Groq returned invalid timestamps in segment ${i + 1}."
                }
                require(start + 0.25 >= previousStart && end <= duration + TIMESTAMP_TOLERANCE_SECONDS) {
                    "Groq returned timestamps outside the audio chunk (segment ${i + 1})."
                }
                if (text.isNotEmpty()) add(ParsedSegment(start, end, text))
                previousStart = start
            }
        }
        require(parsed.isNotEmpty()) { "Groq returned an empty transcript for this audio chunk." }
        return language to parsed
    }

    private fun deduplicate(segments: List<ParsedSegment>): List<ParsedSegment> {
        val result = mutableListOf<ParsedSegment>()
        for (segment in segments) {
            val previous = result.lastOrNull()
            if (previous != null &&
                segment.startSeconds < previous.endSeconds &&
                segment.text.replace(Regex("\\s+"), "") == previous.text.replace(Regex("\\s+"), "")
            ) continue
            result += segment
        }
        return result
    }

    private fun fingerprint(file: File, model: String, language: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("${file.length()}:$model:$language".toByteArray())
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().take(12).joinToString("") { "%02x".format(it) }
    }

    private fun wavDuration(file: File): Long? = readWavHeader(file)?.let { it.dataBytes * 1000L / it.byteRate }

    private fun mediaDuration(file: File): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun writeWavHeader(out: RandomAccessFile, header: WavHeader, dataBytes: Long) {
        val total = dataBytes + 36L
        require(total <= Int.MAX_VALUE) { "WAV chunk is too large." }
        out.writeBytes("RIFF")
        out.writeIntLE(total.toInt())
        out.writeBytes("WAVEfmt ")
        out.writeIntLE(16)
        out.writeShortLE(header.audioFormat)
        out.writeShortLE(header.channels)
        out.writeIntLE(header.sampleRate)
        out.writeIntLE(header.byteRate.toInt())
        out.writeShortLE(header.blockAlign)
        out.writeShortLE(header.bitsPerSample)
        out.writeBytes("data")
        out.writeIntLE(dataBytes.toInt())
    }

    private fun RandomAccessFile.readAscii(count: Int) = ByteArray(count).also(::readFully).toString(Charsets.US_ASCII)
    private fun RandomAccessFile.readIntLE() = Integer.reverseBytes(readInt())
    private fun RandomAccessFile.readUnsignedShortLE() = java.lang.Short.toUnsignedInt(java.lang.Short.reverseBytes(readShort()))
    private fun RandomAccessFile.writeIntLE(value: Int) = writeInt(Integer.reverseBytes(value))
    private fun RandomAccessFile.writeShortLE(value: Int) = writeShort(java.lang.Short.reverseBytes(value.toShort()).toInt())

    private companion object {
        const val MAX_BYTES = 24_000_000L
        const val CHUNK_MS = LONG_MEDIA_CHUNK_MS
        const val TIMESTAMP_TOLERANCE_SECONDS = 1.0
        const val OVERLAP_MS = 1_000L
        const val WAV_HEADER_BYTES = 44
    }
}
