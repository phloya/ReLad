package plus.core.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Shared HTTP plumbing for the three provider formats. */
abstract class HttpAiClient(
    protected val config: AiProviderConfig,
    private val apiKey: String?,
    private val client: OkHttpClient,
) : AiClient {

    protected val json = Json { ignoreUnknownKeys = true }

    protected abstract fun buildRequest(request: AiRequest, apiKey: String): Request

    protected abstract fun parseResponse(body: JsonObject): AiResponse

    override suspend fun send(request: AiRequest): AiResponse {
        val key = apiKey?.takeIf { it.isNotBlank() }
            ?: throw AiException("No API key for ${config.name}", AiException.Kind.NoKey)
        val httpRequest = buildRequest(request, key)
        var attempt = 0
        var answer: Answer
        while (true) {
            answer = try {
                client.newCall(httpRequest).await().use { Answer(it.code, it.body.string(), it.header("Retry-After")) }
            } catch (e: IOException) {
                throw AiException("${config.name}: ${e.message}", AiException.Kind.Network, e)
            }
            // Too many requests or the provider overloaded (several pages at once): wait and ask again
            val busy = answer.code == TOO_MANY_REQUESTS || answer.code in RETRY_SERVER_ERRORS
            if (!busy || attempt >= MAX_RETRIES) break
            attempt++
            val asked = answer.retryAfter?.trim()?.toLongOrNull()?.times(1000)
            delay((asked ?: (RETRY_BASE_MS shl (attempt - 1))).coerceAtMost(MAX_RETRY_WAIT_MS))
        }
        val (code, text) = answer.code to answer.text
        if (code !in 200..299) {
            throw AiException(
                "${config.name}: HTTP $code ${errorMessage(text)}",
                AiException.Kind.Http,
                httpCode = code,
            )
        }
        val body = try {
            withContext(Dispatchers.Default) { json.parseToJsonElement(text) as JsonObject }
        } catch (e: Exception) {
            throw AiException("${config.name}: not a JSON answer", AiException.Kind.BadResponse, e)
        }
        return parseResponse(body)
    }

    protected fun jsonBody(element: JsonElement) =
        json.encodeToString(JsonElement.serializer(), element).toRequestBody(JSON_MEDIA_TYPE)

    protected fun endpoint(path: String): String = config.baseUrl.trimEnd('/') + path

    /** Pulls the human-readable message out of an error body, which every provider formats differently. */
    private fun errorMessage(text: String): String {
        val element = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return text.take(300)
        val error = element["error"]
        val message = (error as? JsonObject)?.get("message") ?: error ?: element["message"]
        return message?.toString()?.trim('"')?.take(300) ?: text.take(300)
    }

    private class Answer(val code: Int, val text: String, val retryAfter: String?)

    companion object {
        private const val TOO_MANY_REQUESTS = 429

        /** Overloaded or briefly down (Anthropic's 529 included): worth asking again. */
        private val RETRY_SERVER_ERRORS = setOf(500, 502, 503, 504, 529)
        private const val MAX_RETRIES = 3
        private const val RETRY_BASE_MS = 2_000L
        private const val MAX_RETRY_WAIT_MS = 30_000L

        val JSON_MEDIA_TYPE = "application/json".toMediaType()

        fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

        fun decodeBase64(data: String): ByteArray = Base64.getDecoder().decode(data)

        /** Decodes `data:image/png;base64,...`. Returns `null` for other URLs. */
        fun decodeDataUrl(url: String): AiImage? {
            if (!url.startsWith("data:")) return null
            val comma = url.indexOf(',')
            if (comma < 0) return null
            val header = url.substring(5, comma)
            val mime = header.substringBefore(';').ifEmpty { "image/png" }
            return AiImage(decodeBase64(url.substring(comma + 1)), mime)
        }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, _, _ -> response.close() }
            }

            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }
        },
    )
    continuation.invokeOnCancellation { runCatching { cancel() } }
}
