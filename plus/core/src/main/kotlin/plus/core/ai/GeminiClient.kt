package plus.core.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
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
 * Google Gemini API: `POST {baseUrl}/v1beta/models/{model}:generateContent` with the key in
 * the `x-goog-api-key` header (official REST API, no SDK needed).
 */
class GeminiClient(
    config: AiProviderConfig,
    apiKey: String?,
    client: OkHttpClient,
) : HttpAiClient(config, apiKey, client) {

    override fun buildRequest(request: AiRequest, apiKey: String): Request {
        val body = buildJsonObject {
            request.fullSystem?.let { system ->
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") { addJsonObject { put("text", system) } }
                }
            }
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        request.images.forEach { image ->
                            addJsonObject {
                                putJsonObject("inlineData") {
                                    put("mimeType", image.mimeType)
                                    put("data", base64(image.bytes))
                                }
                            }
                        }
                        addJsonObject { put("text", request.prompt) }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("maxOutputTokens", request.maxOutputTokens)
                when {
                    request.imageOutput -> {
                        putJsonArray("responseModalities") {
                            add("TEXT")
                            add("IMAGE")
                        }
                        request.imageAspectRatio?.let { ratio ->
                            putJsonObject("imageConfig") { put("aspectRatio", ratio) }
                        }
                    }
                    request.jsonOutput -> put("responseMimeType", "application/json")
                }
                // Gemini 3 and later take a level; "low" is the least every such model accepts
                val least = request.effort == AiEffort.Low || request.effort == AiEffort.None
                if (least && takesThinkingLevel()) {
                    putJsonObject("thinkingConfig") { put("thinkingLevel", "low") }
                }
            }
        }
        val model = config.model.removePrefix("models/")
        return Request.Builder()
            .url(endpoint("/v1beta/models/$model:generateContent"))
            .header("x-goog-api-key", apiKey)
            .post(jsonBody(body))
            .build()
    }

    private fun takesThinkingLevel(): Boolean {
        val model = config.model.removePrefix("models/")
        return model.startsWith("gemini-3") || model.startsWith("gemini-4")
    }

    override fun parseResponse(body: JsonObject): AiResponse {
        val candidate = (body["candidates"] as? JsonArray)?.firstOrNull()?.jsonObject
        if (candidate == null) {
            val blocked = body["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitive?.contentOrNull
            throw AiException(
                "${config.name}: no answer${blocked?.let { " (blocked: $it)" }.orEmpty()}",
                if (blocked != null) AiException.Kind.Refused else AiException.Kind.BadResponse,
            )
        }
        val parts = (candidate["content"]?.jsonObject?.get("parts") as? JsonArray).orEmpty().map { it.jsonObject }
        // Thinking models return their reasoning as parts marked "thought"; that is not the answer
        val answerParts = parts.filter { it["thought"]?.jsonPrimitive?.booleanOrNull != true }
        val text = answerParts.mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }
            .joinToString("").ifEmpty { null }
        val images = answerParts.mapNotNull { part ->
            val data = (part["inlineData"] ?: part["inline_data"])?.jsonObject ?: return@mapNotNull null
            val mime = (data["mimeType"] ?: data["mime_type"])?.jsonPrimitive?.contentOrNull ?: "image/png"
            val payload = data["data"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            AiImage(decodeBase64(payload), mime)
        }
        if (text == null && images.isEmpty()) {
            val reason = candidate["finishReason"]?.jsonPrimitive?.contentOrNull
            throw AiException("${config.name}: empty answer ($reason)", AiException.Kind.BadResponse)
        }
        return AiResponse(text, images, usage(body))
    }

    /** `promptTokenCount` already includes the cached part; reasoning comes as `thoughtsTokenCount`. */
    private fun usage(body: JsonObject): AiUsage? {
        val usage = body["usageMetadata"] as? JsonObject ?: return null
        fun n(key: String) = usage[key]?.jsonPrimitive?.longOrNull ?: 0L
        return AiUsage(
            inputTokens = n("promptTokenCount"),
            cachedInputTokens = n("cachedContentTokenCount"),
            outputTokens = n("candidatesTokenCount") + n("thoughtsTokenCount"),
        )
    }
}
