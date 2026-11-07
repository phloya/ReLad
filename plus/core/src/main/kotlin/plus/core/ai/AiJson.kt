package plus.core.ai

import okhttp3.OkHttpClient

object AiClients {
    fun create(config: AiProviderConfig, apiKey: String?, client: OkHttpClient): AiClient = when (config.type) {
        AiProviderType.OpenAiCompatible -> OpenAiCompatibleClient(config, apiKey, client)
        AiProviderType.Gemini -> GeminiClient(config, apiKey, client)
        AiProviderType.Anthropic -> AnthropicClient(config, apiKey, client)
    }
}

object AiJson {
    /**
     * Models often wrap JSON in ```json fences or add a sentence around it.
     * Returns the outermost `{...}` or `[...]` block, or `null` if there is none.
     */
    fun extract(text: String): String? {
        val start = text.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) return null
        val close = if (text[start] == '{') '}' else ']'
        val end = text.lastIndexOf(close)
        if (end <= start) return null
        return text.substring(start, end + 1)
    }
}
