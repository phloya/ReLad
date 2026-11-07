package plus.core.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * OpenAI Chat Completions format (`POST {baseUrl}/chat/completions`), spoken by OpenAI, OpenRouter
 * and most other providers. Images go in as data URLs. Image output uses OpenRouter's
 * `modalities: ["image", "text"]`, the image comes back in `message.images`.
 *
 * Deliberately sends no `temperature`, `max_tokens` or `response_format`: newer OpenAI models reject
 * some of them and many OpenRouter models ignore or reject the rest. Prompts ask for JSON instead.
 * The wanted reasoning effort goes only to OpenRouter, whose one `reasoning` setting every model
 * that thinks takes (and the others ignore); other servers may reject the field.
 */
class OpenAiCompatibleClient(
    config: AiProviderConfig,
    apiKey: String?,
    client: OkHttpClient,
) : HttpAiClient(config, apiKey, client) {

    override fun buildRequest(request: AiRequest, apiKey: String): Request {
        val body = buildJsonObject {
            put("model", config.model)
            putJsonArray("messages") {
                request.fullSystem?.let { system ->
                    addJsonObject {
                        put("role", "system")
                        put("content", system)
                    }
                }
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", request.prompt)
                        }
                        request.images.forEach { image ->
                            addJsonObject {
                                put("type", "image_url")
                                putJsonObject("image_url") {
                                    put("url", "data:${image.mimeType};base64,${base64(image.bytes)}")
                                }
                            }
                        }
                    }
                }
            }
            if (request.effort != null && isOpenRouter()) {
                putJsonObject("reasoning") { put("effort", request.effort.wire) }
            }
            if (request.imageOutput) {
                putJsonArray("modalities") {
                    add("image")
                    add("text")
                }
            }
        }
        return Request.Builder()
            .url(endpoint("/chat/completions"))
            .header("Authorization", "Bearer $apiKey")
            // OpenRouter shows these in its dashboard; other providers ignore them
            .header("HTTP-Referer", "https://github.com/phloya/ReLad")
            .header("X-Title", "Re:Lad")
            .post(jsonBody(body))
            .build()
    }

    private fun isOpenRouter(): Boolean = config.baseUrl.contains("openrouter.ai")

    override fun parseResponse(body: JsonObject): AiResponse {
        val choice = (body["choices"] as? JsonArray)?.firstOrNull()?.jsonObject
            ?: throw AiException("${config.name}: no answer in response", AiException.Kind.BadResponse)
        val message = choice["message"]?.jsonObject
            ?: throw AiException("${config.name}: no message in response", AiException.Kind.BadResponse)

        val text = when (val content = message["content"]) {
            is JsonPrimitive -> content.contentOrNull
            // Some providers return content as a list of parts
            is JsonArray -> content.mapNotNull { part ->
                part.jsonObject["text"]?.jsonPrimitive?.contentOrNull
            }.joinToString("").ifEmpty { null }
            else -> null
        }
        val images = (message["images"] as? JsonArray).orEmpty().mapNotNull { image ->
            val url = image.jsonObject["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
            url?.let(::decodeDataUrl)
        }
        if (text == null && images.isEmpty()) {
            val reason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
            val kind = if (reason == "content_filter") AiException.Kind.Refused else AiException.Kind.BadResponse
            throw AiException("${config.name}: empty answer ($reason)", kind)
        }
        return AiResponse(text, images, usage(body))
    }

    /** `prompt_tokens` includes the cached part, `completion_tokens` the reasoning. OpenRouter adds `cost`. */
    private fun usage(body: JsonObject): AiUsage? {
        val usage = body["usage"] as? JsonObject ?: return null
        fun n(obj: JsonObject?, key: String) = obj?.get(key)?.jsonPrimitive?.longOrNull ?: 0L
        return AiUsage(
            inputTokens = n(usage, "prompt_tokens"),
            cachedInputTokens = n(usage["prompt_tokens_details"] as? JsonObject, "cached_tokens"),
            outputTokens = n(usage, "completion_tokens"),
            reportedCostUsd = usage["cost"]?.jsonPrimitive?.doubleOrNull,
        )
    }
}
