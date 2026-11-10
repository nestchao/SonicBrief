package dev.sonicbrief.app.data.groq

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

class GroqException(
    val statusCode: Int,
    val code: String?,
    message: String,
    internal var retryAfterMs: Long? = null,
) : IOException(message)

data class GroqSegment(
    val startSeconds: Double,
    val endSeconds: Double,
    val text: String,
    val avgLogprob: Double? = null,
    val noSpeechProb: Double? = null,
)

data class GroqTranscription(val language: String, val segments: List<GroqSegment>)

/** Direct REST client for the Groq OpenAI-compatible audio API, authenticated with the user's own key. */
@Singleton
class GroqApi @Inject constructor() {
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /** Cheap key validation that lists models instead of uploading audio. */
    suspend fun listModels(apiKey: String): List<String> {
        val json = JSONObject(execute(bearer(apiKey, "$BASE/openai/v1/models").get().build()))
        val data = json.optJSONArray("data") ?: JSONArray()
        return buildList {
            for (i in 0 until data.length()) {
                data.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    suspend fun transcribeVerbose(
        apiKey: String,
        file: File,
        mimeType: String,
        model: String,
        languageHint: String?,
    ): GroqTranscription {
        val fileBody = file.asRequestBody(mimeType.toMediaType())
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, fileBody)
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("timestamp_granularities[]", "segment")
        if (!languageHint.isNullOrBlank() && !languageHint.equals("auto", ignoreCase = true)) {
            multipart.addFormDataPart("language", languageHint.trim().lowercase().substringBefore('-'))
        }
        val request = bearer(apiKey, "$BASE/openai/v1/audio/transcriptions")
            .post(multipart.build())
            .build()
        val raw = withRetry { call(request) { it.body?.string().orEmpty() } }
        return GroqJson.parseVerbose(raw)
    }

    private fun bearer(apiKey: String, url: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer $apiKey")

    private suspend fun execute(request: Request): String = withRetry { call(request) { it.body?.string().orEmpty() } }

    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: GroqException) {
                val waitMs = retryDelayMs(failure, attempt)
                if (waitMs == null || attempt >= MAX_ATTEMPTS - 1) throw failure
                delay(waitMs)
                attempt++
            } catch (io: IOException) {
                // Transient transport failure (socket reset, timeout, ...).
                if (attempt >= MAX_ATTEMPTS - 1) throw io
                delay(RETRY_DELAYS_MS[attempt.coerceAtMost(RETRY_DELAYS_MS.lastIndex)])
                attempt++
            }
        }
    }

    private fun retryDelayMs(failure: GroqException, attempt: Int): Long? {
        if (failure.statusCode == 429 || failure.statusCode >= 500) {
            // Honor Retry-After when the provider sends one (seconds or HTTP-date are both seen in the wild).
            failure.retryAfterMs?.let { return it.coerceIn(1_000, 60_000) }
            return RETRY_DELAYS_MS[attempt.coerceAtMost(RETRY_DELAYS_MS.lastIndex)]
        }
        return null
    }

    private suspend fun <T> call(request: Request, read: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            if (!it.isSuccessful) {
                                val body = it.body?.string().orEmpty()
                                throw GroqException(it.code, errorCode(body), errorMessage(body, it.code))
                                    .also { ex -> ex.retryAfterMs = it.header("Retry-After")?.let(::parseRetryAfterMs) }
                            }
                            continuation.resume(read(it))
                        } catch (t: Throwable) {
                            if (continuation.isActive) continuation.resumeWithException(t)
                        }
                    }
                }
            })
        }

    private fun errorCode(body: String): String? = runCatching {
        JSONObject(body).getJSONObject("error").optString("code").takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun errorMessage(body: String, status: Int): String {
        val detail = runCatching {
            JSONObject(body).getJSONObject("error").optString("message", body)
        }.getOrDefault(body).take(400)
        return "Groq HTTP $status: $detail"
    }

    private companion object {
        const val BASE = "https://api.groq.com"
        const val MAX_ATTEMPTS = 4
        val RETRY_DELAYS_MS = longArrayOf(2_000, 5_000, 15_000)
    }
}

private fun parseRetryAfterMs(value: String): Long? = runCatching { value.trim().toLong() * 1000 }.getOrNull()
