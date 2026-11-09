package dev.sonicbrief.app.data.gemini

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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject

class GeminiException(
    val statusCode: Int,
    message: String,
) : IOException(message)

data class UploadedFile(val name: String, val uri: String, val mimeType: String)

/** Direct REST client for the Gemini Files + generateContent APIs, authenticated with the user's own key. */
@Singleton
class GeminiApi @Inject constructor() {
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    suspend fun listModels(apiKey: String): List<String> {
        val names = mutableListOf<String>()
        var pageToken: String? = null
        do {
            val url = buildString {
                append("$BASE/v1beta/models?pageSize=200")
                if (pageToken != null) append("&pageToken=").append(pageToken)
            }
            val json = JSONObject(execute(Request.Builder().url(url).header(KEY_HEADER, apiKey).get().build()))
            val models = json.optJSONArray("models") ?: JSONArray()
            for (i in 0 until models.length()) {
                val model = models.getJSONObject(i)
                val methods = model.optJSONArray("supportedGenerationMethods")
                val supportsGenerate = methods != null &&
                    (0 until methods.length()).any { methods.optString(it) == "generateContent" }
                if (supportsGenerate) names += model.optString("name")
            }
            pageToken = json.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (pageToken != null)
        return GeminiModels.filterAndSort(names)
    }

    suspend fun uploadFile(
        apiKey: String,
        file: File,
        mimeType: String,
        displayName: String,
    ): UploadedFile {
        val startBody = JSONObject().put("file", JSONObject().put("display_name", displayName.take(120)))
            .toString().toRequestBody(JSON)
        val startRequest = Request.Builder()
            .url("$BASE/upload/v1beta/files")
            .header(KEY_HEADER, apiKey)
            .header("X-Goog-Upload-Protocol", "resumable")
            .header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", file.length().toString())
            .header("X-Goog-Upload-Header-Content-Type", mimeType)
            .post(startBody)
            .build()
        val uploadUrl = withRetry { call(startRequest) { response ->
            response.header("x-goog-upload-url")
                ?: throw IOException("Gemini did not return an upload URL.")
        } }

        val finalizeRequest = Request.Builder()
            .url(uploadUrl)
            .header("X-Goog-Upload-Offset", "0")
            .header("X-Goog-Upload-Command", "upload, finalize")
            .post(streamingBody(file, mimeType))
            .build()
        val body = withRetry { call(finalizeRequest) { it.body?.string().orEmpty() } }
        val fileJson = JSONObject(body).getJSONObject("file")
        var uploaded = parseFile(fileJson, mimeType)

        // Larger files are processed asynchronously; wait until the file is usable.
        var state = fileJson.optString("state", "ACTIVE")
        var waited = 0
        while (state == "PROCESSING" && waited < 120) {
            delay(2_000)
            waited += 2
            val refreshed = JSONObject(
                execute(Request.Builder().url("$BASE/v1beta/${uploaded.name}").header(KEY_HEADER, apiKey).get().build()),
            )
            state = refreshed.optString("state", "ACTIVE")
            uploaded = parseFile(refreshed, mimeType)
        }
        if (state == "FAILED") throw IOException("Gemini failed to process the uploaded audio file.")
        return uploaded
    }

    suspend fun deleteFile(apiKey: String, file: UploadedFile) {
        runCatching {
            execute(Request.Builder().url("$BASE/v1beta/${file.name}").header(KEY_HEADER, apiKey).delete().build())
        }
    }

    /** Returns the concatenated text of the first candidate. */
    suspend fun generate(
        apiKey: String,
        model: String,
        file: UploadedFile?,
        prompt: String,
        responseSchema: JSONObject? = null,
        maxOutputTokens: Int = 32_768,
    ): String {
        val parts = JSONArray()
        if (file != null) {
            parts.put(JSONObject().put("file_data", JSONObject().put("mime_type", file.mimeType).put("file_uri", file.uri)))
        }
        parts.put(JSONObject().put("text", prompt))
        val generationConfig = JSONObject().put("maxOutputTokens", maxOutputTokens).put("temperature", 0.2)
        if (responseSchema != null) {
            generationConfig.put("responseMimeType", "application/json").put("responseSchema", responseSchema)
        }
        val payload = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", generationConfig)
        val request = Request.Builder()
            .url("$BASE/v1beta/models/${model.removePrefix("models/")}:generateContent")
            .header(KEY_HEADER, apiKey)
            .post(payload.toString().toRequestBody(JSON))
            .build()
        val raw = withRetry { call(request) { it.body?.string().orEmpty() } }
        return extractText(JSONObject(raw))
    }

    internal fun extractText(root: JSONObject): String {
        val candidates = root.optJSONArray("candidates")
        val parts = candidates?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
        val text = buildString {
            if (parts != null) for (i in 0 until parts.length()) append(parts.optJSONObject(i)?.optString("text").orEmpty())
        }
        if (text.isBlank()) {
            val reason = root.optJSONObject("promptFeedback")?.optString("blockReason")
                ?: candidates?.optJSONObject(0)?.optString("finishReason")
            throw IOException("Gemini returned no text${reason?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""}.")
        }
        return text
    }

    private suspend fun execute(request: Request): String = withRetry { call(request) { it.body?.string().orEmpty() } }

    private suspend fun <T> withRetry(block: suspend () -> T): T {
        val delays = longArrayOf(1_500, 3_000)
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: GeminiException) {
                if (!isRetryable(failure) || attempt >= delays.size) throw failure
                delay(delays[attempt++])
            }
        }
    }

    private fun isRetryable(failure: GeminiException): Boolean =
        failure.statusCode == 429 || failure.statusCode >= 500 ||
            failure.message.orEmpty().contains("overloaded", ignoreCase = true)

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
                                val message = errorMessage(it.body?.string().orEmpty())
                                throw GeminiException(it.code, "Gemini HTTP ${it.code}: $message")
                            }
                            continuation.resume(read(it))
                        } catch (t: Throwable) {
                            if (continuation.isActive) continuation.resumeWithException(t)
                        }
                    }
                }
            })
        }

    private fun errorMessage(body: String): String = runCatching {
        JSONObject(body).getJSONObject("error").optString("message", body)
    }.getOrDefault(body).take(400)

    private fun parseFile(json: JSONObject, fallbackMime: String) = UploadedFile(
        name = json.getString("name"),
        uri = json.getString("uri"),
        mimeType = json.optString("mimeType").ifBlank { fallbackMime },
    )

    private fun streamingBody(file: File, mimeType: String): RequestBody = object : RequestBody() {
        private val inner = file.asRequestBody(mimeType.toMediaType())
        override fun contentType() = inner.contentType()
        override fun contentLength() = inner.contentLength()
        override fun writeTo(sink: BufferedSink) = inner.writeTo(sink)
    }

    private companion object {
        const val BASE = "https://generativelanguage.googleapis.com"
        const val KEY_HEADER = "x-goog-api-key"
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
