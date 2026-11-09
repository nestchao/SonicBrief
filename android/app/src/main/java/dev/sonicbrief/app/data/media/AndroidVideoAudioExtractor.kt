package dev.sonicbrief.app.data.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Singleton
class AndroidVideoAudioExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class ExtractedAudio(
        val file: File,
        val displayName: String,
        val mimeType: String,
        val durationMs: Long,
    )

    suspend fun extract(uri: Uri): Result<ExtractedAudio> = runCatching {
        withContext(Dispatchers.IO) {
            val sourceMime = context.contentResolver.getType(uri)
            if (sourceMime != null) {
                require(sourceMime.startsWith("video/")) { "Select a supported video file." }
            }

            val extractor = MediaExtractor()
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: error("Unable to open the selected video file.")
            descriptor.use { extractor.setDataSource(it.fileDescriptor) }

            var trackIndex = -1
            var discoveredFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    trackIndex = index
                    discoveredFormat = format
                    break
                }
            }
            require(trackIndex >= 0) { "The selected video does not contain a decodable audio track." }
            val inputFormat = requireNotNull(discoveredFormat) {
                "The selected video does not contain a decodable audio track."
            }

            extractor.selectTrack(trackIndex)
            val inputMime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: error("Video audio track has no MIME type.")
            val decoder = MediaCodec.createDecoderByType(inputMime)
            val outputDir = File(context.cacheDir, "video-audio").apply { mkdirs() }
            check(outputDir.isDirectory) { "Unable to create the video-audio cache directory." }
            val outputFile = File.createTempFile("sonicbrief-video-", ".wav", outputDir)

            try {
                decoder.configure(inputFormat, null, null, 0)
                decoder.start()

                var inputEnded = false
                var outputEnded = false
                var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
                var dataBytes = 0L
                val info = MediaCodec.BufferInfo()

                RandomAccessFile(outputFile, "rw").use { wav ->
                    wav.setLength(0L)
                    wav.write(ByteArray(WAV_HEADER_BYTES))

                    while (!outputEnded) {
                        coroutineContext.ensureActive()

                        if (!inputEnded) {
                            val inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                            if (inputIndex >= 0) {
                                val inputBuffer = decoder.getInputBuffer(inputIndex)
                                    ?: error("Audio decoder returned no input buffer.")
                                inputBuffer.clear()
                                val size = extractor.readSampleData(inputBuffer, 0)
                                if (size < 0) {
                                    decoder.queueInputBuffer(
                                        inputIndex,
                                        0,
                                        0,
                                        0L,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                    )
                                    inputEnded = true
                                } else {
                                    decoder.queueInputBuffer(
                                        inputIndex,
                                        0,
                                        size,
                                        extractor.sampleTime.coerceAtLeast(0L),
                                        0,
                                    )
                                    extractor.advance()
                                }
                            }
                        }

                        when (val outputIndex = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val format = decoder.outputFormat
                                val newRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                if (dataBytes > 0L && newRate != sampleRate) {
                                    error("Audio sample rate changed during decoding.")
                                }
                                sampleRate = newRate
                                channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                                pcmEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                    format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                } else {
                                    AudioFormat.ENCODING_PCM_16BIT
                                }
                            }

                            MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                            else -> if (outputIndex >= 0) {
                                decoder.getOutputBuffer(outputIndex)?.let { raw ->
                                    if (info.size > 0) {
                                        raw.position(info.offset)
                                        raw.limit(info.offset + info.size)
                                        val buffer = raw.slice().order(ByteOrder.LITTLE_ENDIAN)
                                        dataBytes += writeMonoPcm16(
                                            source = buffer,
                                            encoding = pcmEncoding,
                                            channels = channels,
                                            destination = wav,
                                        )
                                    }
                                }
                                outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                decoder.releaseOutputBuffer(outputIndex, false)
                            }
                        }
                    }

                    require(dataBytes > 0L) { "The selected video decoded to no audio samples." }
                    require(sampleRate > 0) { "Audio decoder returned an invalid sample rate." }
                    writeWavHeader(wav, sampleRate, dataBytes)
                }

                val sourceName = queryDisplayName(uri)
                    ?: "video-${System.currentTimeMillis()}"
                val baseName = sourceName.substringBeforeLast('.', sourceName)
                    .replace(Regex("[^A-Za-z0-9._-]+"), "-")
                    .trim('-')
                    .take(100)
                    .ifBlank { "video" }
                val sampleCount = dataBytes / Short.SIZE_BYTES

                ExtractedAudio(
                    file = outputFile,
                    displayName = "$baseName-audio.wav",
                    mimeType = "audio/wav",
                    durationMs = sampleCount * 1000L / sampleRate,
                )
            } catch (throwable: Throwable) {
                outputFile.delete()
                throw throwable
            } finally {
                runCatching { decoder.stop() }
                decoder.release()
                extractor.release()
            }
        }
    }

    suspend fun createSilentFallback(uri: Uri): Result<ExtractedAudio> = runCatching {
        withContext(Dispatchers.IO) {
            val sourceMime = context.contentResolver.getType(uri)
            if (sourceMime != null) {
                require(sourceMime.startsWith("video/")) { "Select a supported video file." }
            }

            val outputDir = File(context.cacheDir, "video-audio").apply { mkdirs() }
            check(outputDir.isDirectory) { "Unable to create the video-audio cache directory." }
            val outputFile = File.createTempFile("sonicbrief-video-silent-", ".wav", outputDir)
            val sampleRate = 16_000
            val sampleCount = sampleRate
            val dataBytes = sampleCount * Short.SIZE_BYTES.toLong()

            try {
                RandomAccessFile(outputFile, "rw").use { wav ->
                    wav.setLength(0L)
                    wav.write(ByteArray(WAV_HEADER_BYTES))
                    val silence = ByteArray(8_192)
                    var remaining = dataBytes
                    while (remaining > 0L) {
                        val size = minOf(silence.size.toLong(), remaining).toInt()
                        wav.write(silence, 0, size)
                        remaining -= size
                    }
                    writeWavHeader(wav, sampleRate, dataBytes)
                }

                val sourceName = queryDisplayName(uri)
                    ?: "video-${System.currentTimeMillis()}"
                val baseName = sourceName.substringBeforeLast('.', sourceName)
                    .replace(Regex("[^A-Za-z0-9._-]+"), "-")
                    .trim('-')
                    .take(100)
                    .ifBlank { "video" }

                ExtractedAudio(
                    file = outputFile,
                    displayName = "$baseName-visual-only.wav",
                    mimeType = "audio/wav",
                    durationMs = 1_000L,
                )
            } catch (throwable: Throwable) {
                outputFile.delete()
                throw throwable
            }
        }
    }

    private fun writeMonoPcm16(
        source: ByteBuffer,
        encoding: Int,
        channels: Int,
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

        val output = ByteBuffer.allocate(frameCount * Short.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)

        repeat(frameCount) {
            var sum = 0f
            repeat(channels) {
                sum += when (encoding) {
                    AudioFormat.ENCODING_PCM_FLOAT -> source.float.coerceIn(-1f, 1f)
                    else -> source.short / 32768f
                }
            }
            val mono = (sum / channels).coerceIn(-1f, 1f)
            val value = (mono * 32767f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output.putShort(value.toShort())
        }

        val writtenBytes = output.position().toLong()
        destination.write(output.array())
        return writtenBytes
    }

    private fun writeWavHeader(file: RandomAccessFile, sampleRate: Int, dataBytes: Long) {
        require(dataBytes <= UInt.MAX_VALUE.toLong()) { "Extracted audio is too large for a WAV file." }
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

    private fun queryDisplayName(uri: Uri): String? =
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }

    private companion object {
        const val WAV_HEADER_BYTES = 44
        const val CODEC_TIMEOUT_US = 10_000L
    }
}
