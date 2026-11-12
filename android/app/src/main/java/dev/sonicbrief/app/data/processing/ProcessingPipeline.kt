package dev.sonicbrief.app.data.processing

import dev.sonicbrief.app.data.background.JobSourceKind
import dev.sonicbrief.app.data.background.WorkContract.Bands
import dev.sonicbrief.app.data.gemini.GeminiSummarizer
import dev.sonicbrief.app.data.gemini.GeminiTranscriber
import dev.sonicbrief.app.data.groq.GroqTranscriber
import dev.sonicbrief.app.data.job.JobStore
import dev.sonicbrief.app.data.media.AndroidPublicMediaDownloader
import dev.sonicbrief.app.data.media.AudioPreparer
import dev.sonicbrief.app.data.media.PreparedAudio
import dev.sonicbrief.app.data.media.PublicMediaUrlValidator
import dev.sonicbrief.app.data.settings.SettingsRepository
import dev.sonicbrief.app.domain.model.JobStage
import dev.sonicbrief.app.domain.model.JobStatus
import dev.sonicbrief.app.domain.model.ProcessingProvider
import dev.sonicbrief.app.domain.model.SUMMARY_FAILED_CODE
import java.io.File
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

typealias ProgressReporter = suspend (stage: JobStage, message: String, progress: Int) -> Unit

/** Thrown when the pipeline cannot start (missing key, deleted job, ...). Never retried. */
class PipelineConfigurationException(message: String) : IllegalStateException(message)

@Singleton
class ProcessingPipeline @Inject constructor(
    private val store: JobStore,
    private val settings: SettingsRepository,
    private val downloader: AndroidPublicMediaDownloader,
    private val preparer: AudioPreparer,
    private val transcriber: GeminiTranscriber,
    private val groqTranscriber: GroqTranscriber,
    private val summarizer: GeminiSummarizer,
) {
    fun cancelActiveAcquisition() = downloader.cancelActiveAcquisition()

    suspend fun run(
        jobId: UUID,
        kind: JobSourceKind,
        localPath: String?,
        report: ProgressReporter,
    ) {
        var job = store.get(jobId) ?: throw PipelineConfigurationException("The job no longer exists.")
        if (job.status == JobStatus.CANCELLED) return
        val provider = job.processingProvider
        val saved = settings.current()
        val (apiKey, model) = if (provider == ProcessingProvider.GROQ) {
            val groqKey = settings.groqApiKey()
                ?: throw PipelineConfigurationException("Add your Groq API key in Settings before processing media.")
            groqKey to (job.model?.takeIf { it.isNotBlank() } ?: saved.groqModel)
        } else {
            val geminiKey = settings.apiKey()
                ?: throw PipelineConfigurationException("Add your Gemini API key in Settings before processing media.")
            geminiKey to (job.model?.takeIf { it.isNotBlank() } ?: saved.model)
        }
        // Summaries always run on Gemini, whatever transcribed the audio. Reusing the Groq key
        // and whisper model here made every Groq job end as "Ready" with no summary (HTTP 404).
        val summaryApiKey = settings.apiKey()
        val summaryModel = saved.model

        var downloadDir: File? = null
        var prepared: PreparedAudio? = null
        try {
            // 1. Acquire audio -----------------------------------------------------------------
            when (kind) {
                JobSourceKind.URL -> {
                    report(JobStage.VALIDATING, "Checking the link", 2)
                    val url = PublicMediaUrlValidator.validate(job.sourceUrl.orEmpty()).getOrThrow()
                    report(JobStage.ACQUIRING_MEDIA, "Reading media details", Bands.ACQUIRE_START)
                    val metadata = downloader.fetchMetadata(url).getOrThrow()
                    job = job.copy(
                        title = job.title ?: metadata.title,
                        creator = metadata.creator,
                        durationMs = metadata.durationMs,
                    ).also { store.save(it) }

                    report(JobStage.ACQUIRING_MEDIA, "Downloading audio", Bands.ACQUIRE_START)
                    var lastPercent = -1
                    val downloaded = downloader.downloadAudio(url) { progress ->
                        if (progress.percent >= lastPercent + 3) {
                            lastPercent = progress.percent
                            store.fireAndForget(jobId, JobStage.ACQUIRING_MEDIA, mapPercent(progress.percent))
                        }
                    }.getOrThrow()
                    downloadDir = downloaded.cacheDirectory
                    prepared = PreparedAudio(
                        file = downloaded.file,
                        mimeType = downloaded.mimeType,
                        displayName = downloaded.displayName,
                        durationMs = metadata.durationMs,
                    )
                }

                JobSourceKind.AUDIO_FILE, JobSourceKind.VIDEO_FILE -> {
                    val source = File(localPath ?: throw PipelineConfigurationException("The selected file is missing."))
                    if (!source.exists()) throw PipelineConfigurationException("The selected file is no longer available.")
                    downloadDir = source.parentFile
                    val name = job.sourceFilename ?: source.name
                    report(JobStage.ACQUIRING_MEDIA, "Preparing audio", Bands.ACQUIRE_START)
                    prepared = if (kind == JobSourceKind.VIDEO_FILE) preparer.prepareVideo(source, name)
                    else preparer.prepareAudio(source, name)
                    job = job.copy(durationMs = prepared.durationMs ?: job.durationMs).also { store.save(it) }
                }
            }
            val audio = checkNotNull(prepared)

            // 2. Transcribe ---------------------------------------------------------------------
            val result = if (provider == ProcessingProvider.GROQ) {
                groqTranscriber.transcribe(
                    apiKey = apiKey,
                    model = model,
                    file = audio.file,
                    mimeType = audio.mimeType,
                    displayName = audio.displayName,
                    durationMs = audio.durationMs ?: job.durationMs,
                    languageHint = job.transcriptLanguage,
                ) { stage, percent ->
                    when (stage) {
                        GroqTranscriber.Stage.PREPARING ->
                            report(JobStage.UPLOADING, "Preparing audio for Groq", Bands.ACQUIRE_END + 1)
                        GroqTranscriber.Stage.UPLOADING ->
                            report(JobStage.UPLOADING, "Uploading audio to Groq", Bands.ACQUIRE_END + 2)
                        GroqTranscriber.Stage.TRANSCRIBING ->
                            report(
                                JobStage.TRANSCRIBING,
                                "Transcribing with Groq",
                                Bands.UPLOAD_END + (Bands.TRANSCRIBE_END - Bands.UPLOAD_END) * percent / 100,
                            )
                    }
                }
            } else {
                transcriber.transcribe(
                    apiKey = apiKey,
                    model = model,
                    file = audio.file,
                    mimeType = audio.mimeType,
                    displayName = audio.displayName,
                    durationMs = audio.durationMs ?: job.durationMs,
                    languageHint = job.transcriptLanguage,
                ) { stage, percent ->
                    when (stage) {
                        GeminiTranscriber.Stage.UPLOADING ->
                            report(JobStage.UPLOADING, "Uploading audio to Gemini", Bands.ACQUIRE_END + 2)
                        GeminiTranscriber.Stage.TRANSCRIBING ->
                            report(
                                JobStage.TRANSCRIBING,
                                "Transcribing with Gemini",
                                Bands.UPLOAD_END + (Bands.TRANSCRIBE_END - Bands.UPLOAD_END) * percent / 100,
                            )
                    }
                }
            }

            val segments = result.segments.map {
                Triple((it.startSeconds * 1000).toLong(), (it.endSeconds * 1000).toLong(), it.text)
            }
            val transcript = store.saveTranscript(jobId, result.language, segments)

            // 3. Summarize ----------------------------------------------------------------------
            // A failed summary must not fail a good transcript: the transcript is saved
            // above, so the job completes and the summary can be regenerated from the
            // detail screen. Cancellation still propagates so cancelled jobs never complete.
            var summaryError: String? = null
            if (summaryApiKey == null) {
                // Groq-only users may never have saved a Gemini key. Say so instead of
                // attempting a call that is guaranteed to be rejected.
                summaryError = "Add your Gemini API key in Settings to generate summaries."
            } else {
                report(JobStage.SUMMARIZING, "Writing summary", Bands.SUMMARY)
                try {
                    val summaryText = summarizer.summarize(
                        apiKey = summaryApiKey,
                        model = summaryModel,
                        transcript = transcript,
                        languageCode = job.summaryLanguage,
                        instructions = job.customInstructions,
                    )
                    store.saveSummary(
                        jobId = jobId,
                        transcriptId = transcript.id,
                        transcriptRevision = transcript.revision,
                        language = job.summaryLanguage,
                        content = summaryText,
                        model = summaryModel,
                        instructions = job.customInstructions,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    summaryError = failure.message?.takeIf { it.isNotBlank() } ?: "Summary failed."
                }
            }

            val latest = store.get(jobId) ?: job
            store.save(
                latest.copy(
                    status = JobStatus.COMPLETED,
                    stage = JobStage.COMPLETED,
                    progress = 100,
                    stageDetail = if (summaryError != null) "Transcript ready · summary failed — open to regenerate" else null,
                    model = model,
                    errorCode = if (summaryError != null) SUMMARY_FAILED_CODE else null,
                    errorMessage = summaryError,
                    completedAt = Instant.now(),
                ),
            )
        } finally {
            // Downloaded media is always disposable. Picked local files are kept (the worker removes them
            // once the job reaches a terminal state) so a transient failure can be retried.
            if (kind == JobSourceKind.URL) {
                withContext(NonCancellable) { downloadDir?.deleteRecursively() }
            }
        }
    }

    /** Removes the private copy of a picked local file. */
    fun discardLocalSource(localPath: String?) {
        localPath?.let { File(it).parentFile?.deleteRecursively() }
    }

    private fun mapPercent(percent: Int): Int =
        Bands.ACQUIRE_START + (Bands.ACQUIRE_END - Bands.ACQUIRE_START) * percent.coerceIn(0, 100) / 100

    private fun JobStore.fireAndForget(id: UUID, stage: JobStage, progress: Int) {
        // Download callbacks run on yt-dlp's thread; progress rows are best-effort.
        kotlinx.coroutines.runBlocking { updateProgress(id, stage, progress, "Downloading audio") }
    }
}
