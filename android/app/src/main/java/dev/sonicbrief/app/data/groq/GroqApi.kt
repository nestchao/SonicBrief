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
import org.json.JSONObject

class GroqException(
    val statusCode: Int,
    message: String,
    val retryAfterSeconds: Long? = null,
) : IOException(message) {
    val retryable: Boolean get() = statusCode == 429 || statusCode >= 500
}

/** OpenAI-compatible Groq speech-to-text client. The user's key is sent directly to Groq. */
@Singleton
class GroqApi @Inject constructor() {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /** A lightweight authenticated request used to validate a BYOK key before saving it. */
    suspend fun validateKey(apiKey: String) {
        val request = Request.Builder()
            .url("$BASE/models")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        withRetry {
            call(request) { response ->
                JSONObject(response.body?.string().orEmpty())
            }
        }
    }

    suspend fun transcribe(
        apiKey: String,
        model: String,
        file: File,
        mimeType: String,
        displayName: String,
        languageHint: String,
    ): JSONObject {
        require(file.isFile && file.length() > 0L) { "The audio chunk is missing or empty." }
        require(file.length() <= MAX_UPLOAD_BYTES) {
            "Audio chunk is ${file.length()} bytes, above the safe Groq upload limit."
        }

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("timestamp_granularities[]", "segment")
            .addFormDataPart("temperature", "0")
            .apply {
                languageHint.trim()
                    .takeIf { it.isNotBlank() && !it.equals("auto", ignoreCase = true) }
                    ?.let { addFormDataPart("language", it.substringBefore('-').lowercase()) }
                addFormDataPart(
                    "file",
                    displayName.substringAfterLast('/').take(120).ifBlank { "audio" },
                    file.asRequestBody(mimeType.toMediaType()),
                )
            }
            .build()

        val request = Request.Builder()
            .url("$BASE/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        return withRetry {
            call(request) { response ->
                JSONObject(response.body?.string().orEmpty())
            }
        }
    }

    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: GroqException) {
                if (!failure.retryable || attempt >= RETRY_DELAYS_MS.size) throw failure
                val requested = failure.retryAfterSeconds?.coerceIn(1L, 30L)?.times(1_000L)
                delay(requested ?: RETRY_DELAYS_MS[attempt])
                attempt++
            } catch (failure: IOException) {
                if (attempt >= RETRY_DELAYS_MS.size) throw failure
                delay(RETRY_DELAYS_MS[attempt])
                attempt++
            }
        }
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
                                val message = runCatching {
                                    JSONObject(body).optJSONObject("error")?.optString("message")
                                }.getOrNull()?.takeIf(String::isNotBlank) ?: body.take(400).ifBlank { it.message }
                                throw GroqException(
                                    statusCode = it.code,
                                    message = "Groq HTTP ${it.code}: $message",
                                    retryAfterSeconds = it.header("Retry-After")?.toLongOrNull(),
                                )
                            }
                            continuation.resume(read(it))
                        } catch (failure: Throwable) {
                            if (continuation.isActive) continuation.resumeWithException(failure)
                        }
                    }
                }
            })
        }

    private companion object {
        const val BASE = "https://api.groq.com/openai/v1"
        const val MAX_UPLOAD_BYTES = 24_000_000L
        val RETRY_DELAYS_MS = longArrayOf(1_500L, 3_000L, 6_000L)
    }
}
