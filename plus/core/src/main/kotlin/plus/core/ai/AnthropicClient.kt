package plus.core.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Anthropic Messages API (`POST {baseUrl}/v1/messages`). Reads images, cannot return them.
 *
 * Newer Claude models can decline a request through safety classifiers (HTTP 200 with
 * `stop_reason: "refusal"`). On Anthropic's own API those models get `fallbacks: "default"`:
 * a declined request is re-run on the model Anthropic recommends for that kind of decline.
 */
class AnthropicClient(
    config: AiProviderConfig,
    apiKey: String?,
    client: OkHttpClient,
) : HttpAiClient(config, apiKey, client) {

    override suspend fun send(request: AiRequest): AiResponse {
        if (request.imageOutput) {
            throw AiException("${config.name}: Claude cannot return images", AiException.Kind.Unsupported)
        }
        return super.send(request)
    }

    override fun buildRequest(request: AiRequest, apiKey: String): Request {
        val fallback = usesServerFallback()
        val body = buildJsonObject {
            put("model", config.model)
            put("max_tokens", request.maxOutputTokens)
            if (fallback) put("fallbacks", "default")
            if (request.stableSystem != null) {
                // Cached up to each marker: the fixed part, then the fixed part plus the rest
                putJsonArray("system") {
                    listOfNotNull(request.stableSystem, request.system).forEach { text ->
                        addJsonObject {
                            put("type", "text")
                            put("text", text)
                            putJsonObject("cache_control") { put("type", "ephemeral") }
                        }
                    }
                }
            } else if (request.system != null) {
                put("system", request.system)
            }
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        request.images.forEach { image ->
                            addJsonObject {
                                put("type", "image")
                                putJsonObject("source") {
                                    put("type", "base64")
                                    put("media_type", image.mimeType)
                                    put("data", base64(image.bytes))
                                }
                            }
                        }
                        addJsonObject {
                            put("type", "text")
                            put("text", request.prompt)
                        }
                    }
                }
            }
        }
        return Request.Builder()
            .url(endpoint("/v1/messages"))
            .header("x-api-key", apiKey)
            .header("anthropic-version", API_VERSION)
            .apply { if (fallback) header("anthropic-beta", FALLBACK_BETA) }
            .post(jsonBody(body))
            .build()
    }

    /** Only models that support `fallbacks: "default"`, and only on Anthropic's API (not proxies or other clouds). */
    private fun usesServerFallback(): Boolean =
        config.model in FALLBACK_MODELS && config.baseUrl.trimEnd('/') == AiProviderType.Anthropic.defaultBaseUrl

    override fun parseResponse(body: JsonObject): AiResponse {
        if (body["stop_reason"]?.jsonPrimitive?.contentOrNull == "refusal") {
            val category = (body["stop_details"] as? JsonObject)?.get("category")?.jsonPrimitive?.contentOrNull
            throw AiException(
                "${config.name}: Claude declined the request" + (category?.let { " ($it)" } ?: ""),
                AiException.Kind.BadResponse,
            )
        }
        val text = (body["content"] as? JsonArray).orEmpty()
            .map { it.jsonObject }
            .filter { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
            .mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }
            .joinToString("")
            .ifEmpty { null }
            ?: throw AiException(
                "${config.name}: empty answer (${body["stop_reason"]?.jsonPrimitive?.contentOrNull})",
                AiException.Kind.BadResponse,
            )
        return AiResponse(text, emptyList(), usage(body))
    }

    /** Anthropic reports uncached input, cache writes and cache reads separately. */
    private fun usage(body: JsonObject): AiUsage? {
        val usage = body["usage"] as? JsonObject ?: return null
        fun n(key: String) = usage[key]?.jsonPrimitive?.longOrNull ?: 0L
        val write = n("cache_creation_input_tokens")
        val read = n("cache_read_input_tokens")
        return AiUsage(
            inputTokens = n("input_tokens") + write + read,
            cachedInputTokens = read,
            cacheWriteTokens = write,
            outputTokens = n("output_tokens"),
        )
    }

    companion object {
        const val API_VERSION = "2023-06-01"
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"

        /** Models that accept `fallbacks: "default"` (checked 2026-11-07). */
        val FALLBACK_MODELS = setOf("claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5")
    }
}
