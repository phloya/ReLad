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
            if (request.system != null) {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") { addJsonObject { put("text", request.system) } }
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
                    request.imageOutput -> putJsonArray("responseModalities") {
                        add("TEXT")
                        add("IMAGE")
                    }
                    request.jsonOutput -> put("responseMimeType", "application/json")
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

    override fun parseResponse(body: JsonObject): AiResponse {
        val candidate = (body["candidates"] as? JsonArray)?.firstOrNull()?.jsonObject
        if (candidate == null) {
            val blocked = body["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitive?.contentOrNull
            throw AiException(
                "${config.name}: no answer${blocked?.let { " (blocked: $it)" }.orEmpty()}",
                AiException.Kind.BadResponse,
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
        return AiResponse(text, images)
    }
}
