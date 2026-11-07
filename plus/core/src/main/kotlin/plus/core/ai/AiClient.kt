package plus.core.ai

import kotlinx.serialization.Serializable

/** Which official API a provider speaks. */
@Serializable
enum class AiProviderType(val defaultBaseUrl: String) {
    /** OpenAI Chat Completions format: OpenAI, OpenRouter and most other providers and local servers. */
    OpenAiCompatible("https://openrouter.ai/api/v1"),

    /** Google Gemini API (generateContent). */
    Gemini("https://generativelanguage.googleapis.com"),

    /** Anthropic Messages API (Claude). */
    Anthropic("https://api.anthropic.com"),
}

/**
 * A provider the user added: where to send requests and which model to use.
 * The API key is not here: it lives encrypted in [plus.core.secret.SecretStore] under [id].
 */
@Serializable
data class AiProviderConfig(
    val id: String,
    val name: String,
    val type: AiProviderType,
    val baseUrl: String,
    val model: String,
)

class AiImage(val bytes: ByteArray, val mimeType: String)

class AiRequest(
    val system: String?,
    val prompt: String,
    val images: List<AiImage> = emptyList(),
    /** Ask the model for an image back (colorization). Not every provider or model can do it. */
    val imageOutput: Boolean = false,
    /**
     * Wanted shape of the returned image ("2:3", "3:4"...), where the API lets us say it
     * (Gemini `imageConfig.aspectRatio`). Otherwise models usually keep the input's shape.
     */
    val imageAspectRatio: String? = null,
    /** Ask for a JSON answer where the API supports a JSON mode. The prompt must describe the format too. */
    val jsonOutput: Boolean = false,
    val maxOutputTokens: Int = 16_384,
)

class AiResponse(val text: String?, val images: List<AiImage>)

class AiException(message: String, val kind: Kind, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind {
        /** No API key saved for the provider. */
        NoKey,

        /** The provider answered with an HTTP error (bad key, quota, unknown model...). */
        Http,

        /** The request never got an answer (no internet, timeout). */
        Network,

        /** The answer could not be understood, or the model refused. */
        BadResponse,

        /** The provider type cannot do what was asked (e.g. Claude cannot return images). */
        Unsupported,
    }
}

interface AiClient {
    suspend fun send(request: AiRequest): AiResponse
}
