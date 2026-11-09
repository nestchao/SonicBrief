package dev.sonicbrief.app.data.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Audio ready to be uploaded to Gemini. */
data class PreparedAudio(
    val file: File,
    val mimeType: String,
    val displayName: String,
    val durationMs: Long?,
)

object AudioMimeTypes {
    fun forExtension(extension: String): String? = when (extension.lowercase()) {
        "m4a", "mp4" -> "audio/mp4"
        "webm" -> "audio/webm"
        "mp3" -> "audio/mpeg"
        "aac" -> "audio/aac"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/opus"
        "wav", "wave" -> "audio/wav"
        "flac" -> "audio/flac"
        "amr" -> "audio/amr"
        else -> null
    }
}

/** Turns a user-selected local audio or video file into something Gemini can ingest. */
@Singleton
class AudioPreparer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val videoExtractor: AndroidVideoAudioExtractor,
) {
    suspend fun prepareAudio(file: File, displayName: String): PreparedAudio {
        val mime = AudioMimeTypes.forExtension(file.extension)
            ?: error("Audio format .${file.extension} is not supported. Use MP3, M4A, WAV, FLAC, OGG, AAC or Opus.")
        return PreparedAudio(file, mime, displayName, durationOf(file))
    }

    /** Fast path: copy the AAC track into an .m4a container. Falls back to decoding to WAV. */
    suspend fun prepareVideo(file: File, displayName: String): PreparedAudio {
        val remuxed = remuxAacTrack(file)
        if (remuxed != null) {
            return PreparedAudio(remuxed, "audio/mp4", displayName, durationOf(remuxed))
        }
        val extracted = videoExtractor.extract(Uri.fromFile(file)).getOrThrow()
        return PreparedAudio(extracted.file, extracted.mimeType, displayName, extracted.durationMs)
    }

    private suspend fun remuxAacTrack(source: File): File? = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        val output = File(source.parentFile, "audio-${System.currentTimeMillis()}.m4a")
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(source.absolutePath)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm"
            } ?: return@withContext null
            extractor.selectTrack(index)
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val track = muxer.addTrack(extractor.getTrackFormat(index))
            muxer.start()
            val buffer = ByteBuffer.allocate(1 shl 20)
            val info = android.media.MediaCodec.BufferInfo()
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
                muxer.writeSampleData(track, buffer, info)
                extractor.advance()
            }
            muxer.stop()
            output.takeIf { it.length() > 0 }
        } catch (_: Throwable) {
            output.delete()
            null
        } finally {
            runCatching { muxer?.release() }
            extractor.release()
        }
    }

    private suspend fun durationOf(file: File): Long? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
