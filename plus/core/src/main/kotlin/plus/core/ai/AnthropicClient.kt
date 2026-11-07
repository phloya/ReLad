package plus.core.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/** Anthropic Messages API (`POST {baseUrl}/v1/messages`). Reads images, cannot return them. */
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
        val body = buildJsonObject {
            put("model", config.model)
            put("max_tokens", request.maxOutputTokens)
            if (request.system != null) put("system", request.system)
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
            .post(jsonBody(body))
            .build()
    }

    override fun parseResponse(body: JsonObject): AiResponse {
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
        return AiResponse(text, emptyList())
    }

    companion object {
        const val API_VERSION = "2023-06-01"
    }
}
