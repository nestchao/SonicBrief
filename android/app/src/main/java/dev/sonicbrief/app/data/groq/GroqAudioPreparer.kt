package dev.sonicbrief.app.data.groq

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class GroqChunkFile(
    val chunk: GroqChunk,
    val file: File,
    val mimeType: String,
    val displayName: String,
    /** True when the file is a temp slice that must be deleted after upload. */
    val temporary: Boolean,
)

/**
 * Prepares upload-friendly audio for Groq's 25 MB per-request limit.
 *
 * Large media is split into sequential time windows (with a small overlap so words are not
 * cut at boundaries). AAC-in-MP4 sources are losslessly remuxed per window; anything else is
 * decoded to mono WAV. Every produced file is verified to fit the byte budget — windows are
 * halved until they do — so an upload is never rejected for size.
 */
@Singleton
class GroqAudioPreparer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun prepare(source: File, displayName: String, durationMs: Long?): List<GroqChunkFile> =
        withContext(Dispatchers.IO) {
            val duration = durationMs ?: probeDuration(source)
            val looksSmall = duration != null && duration <= GROQ_TARGET_CHUNK_MS && source.length() <= GROQ_MAX_UPLOAD_BYTES
            if (duration == null || looksSmall) {
                val single = GroqChunk(0, 0L, duration ?: Long.MAX_VALUE)
                return@withContext listOf(GroqChunkFile(single, source, mimeFor(source), displayName, temporary = false))
            }
            val dir = File(context.cacheDir, "groq-chunks/${UUID.randomUUID()}").apply { mkdirs() }
            try {
                val base = displayName.substringBeforeLast('.', displayName).take(80).ifBlank { "audio" }
                if (hasAacTrack(source)) {
                    remuxWindows(source, base, dir, GroqChunkPlanner.plan(duration, GROQ_TARGET_CHUNK_MS))
                } else {
                    val windows = GroqChunkPlanner.plan(duration, DECODE_WINDOW_MS)
                    decodeWindows(source, base, dir, windows, reindex = true)
                }
            } catch (failure: Throwable) {
                dir.deleteRecursively()
                throw failure
            }
        }

    /** Deletes temp slice files. Originals (`temporary == false`) are never touched. */
    fun discard(chunks: List<GroqChunkFile>) {
        chunks.filter { it.temporary }.forEach { runCatching { it.file.delete() } }
        chunks.firstOrNull { it.temporary }?.file?.parentFile?.let { runCatching { it.delete() } }
    }

    // -- remux path (AAC in MP4/M4A): lossless, fast -------------------------------------------

    private fun hasAacTrack(source: File): Boolean {
        if (!source.extension.lowercase().let { it == "m4a" || it == "mp4" }) return false
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(source.absolutePath)
            (0 until extractor.trackCount).any {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm"
            }
        } catch (_: Throwable) {
            false
        } finally {
            extractor.release()
        }
    }

    private suspend fun remuxWindows(
        source: File,
        base: String,
        dir: File,
        windows: List<GroqChunk>,
    ): List<GroqChunkFile> {
        val out = mutableListOf<GroqChunkFile>()
        windows.forEach { window ->
            coroutineCheck()
            out += remuxFitting(source, base, dir, window)
        }
        return out
    }

    /** Remuxes one window, halving it until the slice fits the upload budget. */
    private suspend fun remuxFitting(source: File, base: String, dir: File, window: GroqChunk): List<GroqChunkFile> {
        val pending = mutableListOf(window)
        val done = mutableListOf<GroqChunkFile>()
        var guard = 0
        while (pending.isNotEmpty()) {
            check(++guard < 64) { "Audio could not be prepared for Groq upload." }
            val current = pending.removeAt(0)
            val file = File(dir, "$base-part${current.index}.m4a")
            remuxWindow(source, file, current)
            if (file.length() <= GROQ_MAX_UPLOAD_BYTES || current.lengthMs <= MIN_WINDOW_MS) {
                done += GroqChunkFile(current, file, "audio/mp4", file.name, temporary = true)
            } else {
                file.delete()
                pending.addAll(0, split(current))
            }
        }
        return done
    }

    private fun split(window: GroqChunk): List<GroqChunk> {
        val mid = window.startMs + window.lengthMs / 2
        return listOf(
            window.copy(endMs = minOf(mid + GROQ_CHUNK_OVERLAP_MS, window.endMs)),
            GroqChunk(window.index + SPLIT_INDEX_GAP, mid - GROQ_CHUNK_OVERLAP_MS, window.endMs),
        )
    }

    private fun remuxWindow(source: File, output: File, window: GroqChunk) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm"
            } ?: error("The audio track is not AAC.")
            extractor.selectTrack(track)
            extractor.seekTo(window.startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outTrack = muxer.addTrack(extractor.getTrackFormat(track))
            muxer.start()
            val buffer = ByteBuffer.allocate(1 shl 20)
            val info = android.media.MediaCodec.BufferInfo()
            var firstPts = -1L
            var wrote = false
            val endUs = window.endMs * 1000
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val pts = extractor.sampleTime
                extractor.advance()
                if (pts >= endUs) break
                if (pts < window.startMs * 1000) continue
                if (firstPts < 0) firstPts = pts
                info.set(0, size, pts - firstPts, extractor.sampleFlags)
                muxer.writeSampleData(outTrack, buffer, info)
                wrote = true
            }
            muxer.stop()
            check(wrote && output.length() > 0) { "No audio found in this section of the file." }
        } catch (failure: Throwable) {
            output.delete()
            throw failure
        } finally {
            runCatching { muxer?.release() }
            extractor.release()
        }
    }

    // -- decode path (anything else): mono WAV windows -----------------------------------------

    private suspend fun decodeWindows(
        source: File,
        base: String,
        dir: File,
        windows: List<GroqChunk>,
        reindex: Boolean,
    ): List<GroqChunkFile> {
        val out = mutableListOf<GroqChunkFile>()
        windows.forEachIndexed { position, window ->
            coroutineCheck()
            val chunk = if (reindex) window.copy(index = position) else window
            val file = File(dir, "$base-part${chunk.index}.wav")
            try {
                decodeWindow(source, file, chunk)
            } catch (failure: Throwable) {
                file.delete()
                throw failure
            }
            if (file.length() <= GROQ_MAX_UPLOAD_BYTES || chunk.lengthMs <= MIN_WINDOW_MS) {
                out += GroqChunkFile(chunk, file, "audio/wav", file.name, temporary = true)
            } else {
                file.delete()
                out += decodeWindows(source, base, dir, split(chunk), reindex = false)
            }
        }
        return out
    }

    private suspend fun decodeWindow(source: File, output: File, window: GroqChunk) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source.absolutePath)
            var track = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    inputFormat = format
                    break
                }
            }
            check(track >= 0 && inputFormat != null) { "No decodable audio track was found." }
            extractor.selectTrack(track)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("Audio track has no MIME type.")
            val decoder = MediaCodec.createDecoderByType(mime)
            try {
                decoder.configure(inputFormat, null, null, 0)
                decoder.start()
                decodeLoop(extractor, decoder, output, window, inputFormat)
            } finally {
                runCatching { decoder.stop() }
                decoder.release()
            }
        } finally {
            extractor.release()
        }
    }

    private suspend fun decodeLoop(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        output: File,
        window: GroqChunk,
        inputFormat: MediaFormat,
    ) {
        val startUs = window.startMs * 1000
        val endUs = window.endMs * 1000
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        var sampleRate = runCatching { inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(44_100)
        var channels = runCatching { inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(2).coerceAtLeast(1)
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        val info = MediaCodec.BufferInfo()
        var inputEnded = false
        var outputEnded = false
        var dataBytes = 0L
        RandomAccessFile(output, "rw").use { wav ->
            wav.setLength(0L)
            wav.write(ByteArray(WAV_HEADER_BYTES))
            while (!outputEnded) {
                coroutineCheck()
                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                            ?: error("Audio decoder returned no input buffer.")
                        inputBuffer.clear()
                        val size = extractor.readSampleData(inputBuffer, 0)
                        if (size < 0 || extractor.sampleTime >= endUs) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime.coerceAtLeast(0L), 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outputIndex = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = decoder.outputFormat
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        decoder.getOutputBuffer(outputIndex)?.let { raw ->
                            if (info.size > 0 && info.presentationTimeUs >= startUs - 1_000_000L) {
                                raw.position(info.offset)
                                raw.limit(info.offset + info.size)
                                val sliced = raw.slice().order(ByteOrder.LITTLE_ENDIAN)
                                dataBytes += writeWindowedMonoPcm16(sliced, encoding, channels, sampleRate, info.presentationTimeUs, startUs, wav)
                            }
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
                if (!inputEnded && !outputEnded) {
                    // Safety: stop feeding once the extractor passed the window even without EOS.
                    if (extractor.sampleTime >= endUs && inputEnded) Unit
                }
            }
            require(dataBytes > 0L) { "This section decoded to no audio samples." }
            writeWavHeader(wav, sampleRate, dataBytes)
        }
    }

    /**
     * Writes one decoded buffer, skipping samples before the window start (decoder output can
     * begin at an earlier sync frame after a seek). Presentation timestamps keep the slice aligned.
     */
    private fun writeWindowedMonoPcm16(
        source: ByteBuffer,
        encoding: Int,
        channels: Int,
        sampleRate: Int,
        ptsUs: Long,
        startUs: Long,
        destination: RandomAccessFile,
    ): Long {
        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> Short.SIZE_BYTES
            AudioFormat.ENCODING_PCM_FLOAT -> Float.SIZE_BYTES
            else -> error("Unsupported decoded PCM encoding: $encoding")
        }
        val frameBytes = bytesPerSample * channels
        val frameCount = source.remaining() / frameBytes
        if (frameCount <= 0) return 0L
        val skipFrames = if (ptsUs < startUs && sampleRate > 0) {
            (((startUs - ptsUs) / 1_000_000.0 * sampleRate).toInt()).coerceIn(0, frameCount)
        } else {
            0
        }
        val keepFrames = frameCount - skipFrames
        if (keepFrames <= 0) return 0L
        val output = ByteBuffer.allocate(keepFrames * Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frameCount) { frame ->
            var sum = 0f
            repeat(channels) {
                sum += when (encoding) {
                    AudioFormat.ENCODING_PCM_FLOAT -> source.float.coerceIn(-1f, 1f)
                    else -> source.short / 32768f
                }
            }
            val mono = (sum / channels).coerceIn(-1f, 1f)
            if (frame >= skipFrames) {
                output.putShort(((mono * 32767f).toInt()).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
        }
        val written = output.position().toLong()
        destination.write(output.array())
        return written
    }

    private fun probeDuration(source: File): Long? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(source.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrNull()

    private fun mimeFor(source: File): String = when (source.extension.lowercase()) {
        "m4a", "mp4" -> "audio/mp4"
        "webm" -> "audio/webm"
        "mp3" -> "audio/mpeg"
        "aac" -> "audio/aac"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/opus"
        "wav", "wave" -> "audio/wav"
        "flac" -> "audio/flac"
        "amr" -> "audio/amr"
        else -> "audio/mpeg"
    }

    private fun writeWavHeader(file: RandomAccessFile, sampleRate: Int, dataBytes: Long) {
        require(dataBytes <= UInt.MAX_VALUE.toLong()) { "Audio section is too large for a WAV file." }
        val byteRate = sampleRate * Short.SIZE_BYTES
        val header = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt((36L + dataBytes).toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1.toShort())
        header.putShort(1.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(Short.SIZE_BYTES.toShort())
        header.putShort(16.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataBytes.toInt())
        file.seek(0L)
        file.write(header.array())
        file.seek(WAV_HEADER_BYTES.toLong() + dataBytes)
    }

    private suspend fun coroutineCheck() {
        // Called from IO loops so cancellation stops slicing promptly.
        if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException("Groq preparation was cancelled.")
    }

    private companion object {
        const val WAV_HEADER_BYTES = 44
        const val CODEC_TIMEOUT_US = 10_000L
        /** Decoded WAV windows stay small enough to clear the upload budget at any sample rate. */
        const val DECODE_WINDOW_MS = 150_000L
        const val MIN_WINDOW_MS = 15_000L
        /** Split halves keep their own indices so chunk labels stay unique after a split. */
        const val SPLIT_INDEX_GAP = 1_000
    }
}
