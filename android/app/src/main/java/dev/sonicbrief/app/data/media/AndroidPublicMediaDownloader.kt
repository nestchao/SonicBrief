package dev.sonicbrief.app.data.media

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Phone-side public URL metadata and audio acquisition for M7.
 *
 * SonicBrief requests an existing audio-only stream and does not invoke an
 * FFmpeg post-processor. The yt-dlp runtime can update independently of the
 * SonicBrief APK, which is important for fast-moving extractor changes.
 */
@Singleton
class AndroidPublicMediaDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class Progress(
        val percent: Int,
        val etaSeconds: Long?,
    )

    data class PublicMediaMetadata(
        val title: String?,
        val creator: String?,
        val platform: String,
        val durationMs: Long?,
        val thumbnailUrl: String?,
        val originalLanguage: String?,
        val webpageUrl: String?,
        val manualSubtitleLanguages: List<String>,
        val automaticCaptionLanguages: List<String>,
    )

    data class DownloadedAudio(
        val file: File,
        val displayName: String,
        val mimeType: String,
        val cacheDirectory: File,
    ) {
        fun cleanup() {
            cacheDirectory.deleteRecursively()
        }
    }


    /** Optional user-imported cookies.txt (YouTube/Google/Bilibili only), passed to yt-dlp when present. */
    private fun YoutubeDLRequest.withCommonOptions(platform: PublicMediaPlatform): YoutubeDLRequest {
        val cookies = File(context.filesDir, COOKIES_FILE_NAME)
        if (cookies.isFile && cookies.length() > 0L) addOption("--cookies", cookies.absolutePath)
        if (platform == PublicMediaPlatform.BILIBILI) {
            // Bilibili's CDN frequently drops long connections; resume and fetch in small chunks.
            addOption("--continue")
            addOption("--retries", "10")
            addOption("--fragment-retries", "10")
            addOption("--http-chunk-size", "5M")
        }
        return this
    }

    private val activeProcessLock = Any()
    private var activeProcessId: String? = null

    fun cancelActiveAcquisition(): Boolean {
        val processId = synchronized(activeProcessLock) { activeProcessId } ?: return false
        return YoutubeDL.getInstance().destroyProcessById(processId)
    }

    suspend fun fetchMetadata(url: ValidatedPublicMediaUrl): Result<PublicMediaMetadata> = runCatching {
        withContext(Dispatchers.IO) {
            val youtubeDl = YoutubeDL.getInstance()
            youtubeDl.init(context)

            suspend fun executeMetadataDump(): String {
                val processId = "sonicbrief-metadata-${UUID.randomUUID()}"
                val response = executeTracked(processId) {
                    runInterruptible(Dispatchers.IO) {
                        youtubeDl.execute(
                            YoutubeDLRequest(url.normalizedUrl)
                                .addOption("--no-playlist")
                                .addOption("--skip-download")
                                .addOption("--dump-single-json")
                                .addOption("--no-warnings")
                                .withCommonOptions(url.platform),
                            processId,
                        )
                    }
                }
                return response.out.trim().also {
                    require(it.isNotBlank()) { "yt-dlp returned empty media metadata." }
                }
            }

            val json = try {
                executeMetadataDump()
            } catch (firstFailure: Throwable) {
                if (firstFailure is CancellationException) throw firstFailure
                if (url.platform != PublicMediaPlatform.YOUTUBE || !isHttp403(firstFailure)) {
                    throw firstFailure
                }

                updateStableYoutubeDlAfter403(youtubeDl)
                executeMetadataDump()
            }

            parseMetadataJson(json, url.platform, url.normalizedUrl)
        }
    }

    suspend fun downloadAudio(
        url: ValidatedPublicMediaUrl,
        onProgress: (Progress) -> Unit = {},
    ): Result<DownloadedAudio> = runCatching {
        withContext(Dispatchers.IO) {
            val youtubeDl = YoutubeDL.getInstance()
            youtubeDl.init(context)

            val workDir = File(
                context.cacheDir,
                "public-media/${UUID.randomUUID()}",
            )
            check(workDir.mkdirs() || workDir.isDirectory) {
                "Unable to create the public-media cache directory."
            }

            try {
                val outputTemplate = File(workDir, "audio.%(ext)s").absolutePath

                fun buildRequest(): YoutubeDLRequest = YoutubeDLRequest(url.normalizedUrl)
                    .addOption("--no-playlist")
                    .addOption("--restrict-filenames")
                    .addOption(
                        "-f",
                        "bestaudio[ext=m4a]/bestaudio[ext=webm]/bestaudio",
                    )
                    .addOption("-o", outputTemplate)
                    .withCommonOptions(url.platform)

                suspend fun executeDownload() {
                    val processId = "sonicbrief-${UUID.randomUUID()}"
                    executeTracked(processId) {
                        runInterruptible(Dispatchers.IO) {
                            youtubeDl.execute(buildRequest(), processId) { progress, etaSeconds, _ ->
                                onProgress(
                                    Progress(
                                        percent = progress.coerceIn(0f, 100f).toInt(),
                                        etaSeconds = etaSeconds.takeIf { it > 0L },
                                    ),
                                )
                            }
                        }
                    }
                }

                try {
                    executeDownload()
                } catch (firstFailure: Throwable) {
                    if (firstFailure is CancellationException) throw firstFailure
                    if (url.platform != PublicMediaPlatform.YOUTUBE || !isHttp403(firstFailure)) {
                        throw firstFailure
                    }

                    workDir.listFiles().orEmpty().forEach { it.deleteRecursively() }
                    updateStableYoutubeDlAfter403(youtubeDl)

                    try {
                        executeDownload()
                    } catch (retryFailure: Throwable) {
                        if (retryFailure is CancellationException) throw retryFailure
                        if (isHttp403(retryFailure)) {
                            val version = youtubeDl.versionName(context) ?: "latest stable"
                            throw IllegalStateException(
                                "YouTube still returned HTTP 403 after updating yt-dlp to $version. " +
                                    "This URL may currently require a newer extractor build or authentication.",
                                retryFailure,
                            )
                        }
                        throw retryFailure
                    }
                }

                val downloadedFile = workDir
                    .listFiles()
                    .orEmpty()
                    .filter { file ->
                        file.isFile &&
                            file.name.startsWith("audio.") &&
                            !file.name.endsWith(".part", ignoreCase = true)
                    }
                    .maxByOrNull(File::length)
                    ?: error("yt-dlp completed but no audio file was produced.")

                require(downloadedFile.length() > 0L) {
                    "The downloaded audio file is empty."
                }

                val extension = downloadedFile.extension.lowercase()
                val mimeType = audioMimeType(extension)
                    ?: error("Downloaded audio format .$extension is not supported yet.")

                DownloadedAudio(
                    file = downloadedFile,
                    displayName = "${platformStorageLabel(url.platform)}-audio-${System.currentTimeMillis()}.$extension",
                    mimeType = mimeType,
                    cacheDirectory = workDir,
                )
            } catch (throwable: Throwable) {
                workDir.deleteRecursively()
                throw throwable
            }
        }
    }

    private suspend fun <T> executeTracked(
        processId: String,
        block: suspend () -> T,
    ): T {
        synchronized(activeProcessLock) {
            check(activeProcessId == null) { "Another public-media acquisition is already running." }
            activeProcessId = processId
        }
        return try {
            block()
        } catch (_: YoutubeDL.CanceledException) {
            throw CancellationException("Public-media acquisition cancelled.")
        } finally {
            synchronized(activeProcessLock) {
                if (activeProcessId == processId) activeProcessId = null
            }
        }
    }

    internal fun parseMetadataJson(
        json: String,
        platform: PublicMediaPlatform,
        fallbackUrl: String,
    ): PublicMediaMetadata {
        val payload = JSONObject(json)
        val durationSeconds = payload.optDouble("duration", Double.NaN)
        val durationMs = durationSeconds
            .takeIf { it.isFinite() && it >= 0.0 }
            ?.let { Math.round(it * 1000.0) }

        val subtitleLanguages = payload.languageKeys("subtitles")
        val nativeAutomaticLanguages = payload.languageKeys("automatic_captions")
        val classified = classifySubtitleLanguages(
            platform = platform,
            subtitleLanguages = subtitleLanguages,
            automaticCaptionLanguages = nativeAutomaticLanguages,
        )

        return PublicMediaMetadata(
            title = payload.optionalString("title"),
            creator = payload.optionalString("uploader")
                ?: payload.optionalString("channel")
                ?: payload.optionalString("creator"),
            platform = platformStorageLabel(platform),
            durationMs = durationMs,
            thumbnailUrl = payload.optionalString("thumbnail"),
            originalLanguage = payload.optionalString("language"),
            webpageUrl = payload.optionalString("webpage_url") ?: fallbackUrl,
            manualSubtitleLanguages = classified.manual,
            automaticCaptionLanguages = classified.automatic,
        )
    }

    private fun JSONObject.optionalString(key: String): String? =
        optString(key, "")
            .trim()
            .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

    private fun JSONObject.languageKeys(key: String): List<String> {
        val languages = optJSONObject(key) ?: return emptyList()
        val result = mutableListOf<String>()
        val iterator = languages.keys()
        while (iterator.hasNext()) {
            val language = iterator.next().trim()
            if (language.isNotBlank() && !language.equals("live_chat", ignoreCase = true)) {
                result += language
            }
        }
        return result.distinct().sorted().take(50)
    }

    private fun updateStableYoutubeDlAfter403(youtubeDl: YoutubeDL) {
        try {
            youtubeDl.updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
        } catch (updateFailure: Throwable) {
            throw IllegalStateException(
                "YouTube returned HTTP 403 and the yt-dlp stable update failed: " +
                    (updateFailure.message ?: "unknown update error"),
                updateFailure,
            )
        }
    }

    private fun platformStorageLabel(platform: PublicMediaPlatform): String = when (platform) {
        PublicMediaPlatform.YOUTUBE -> "youtube"
        PublicMediaPlatform.BILIBILI -> "bilibili"
        PublicMediaPlatform.GENERIC -> "generic"
    }

    private fun isHttp403(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        while (current != null) {
            val message = current.message.orEmpty()
            if (
                message.contains("HTTP Error 403", ignoreCase = true) ||
                message.contains("403: Forbidden", ignoreCase = true)
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    internal fun audioMimeType(extension: String): String? = AudioMimeTypes.forExtension(extension)

    companion object {
        const val COOKIES_FILE_NAME = "cookies.txt"
    }
}
