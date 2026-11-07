package plus.core.ai

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

object AiClients {
    fun create(config: AiProviderConfig, apiKey: String?, client: OkHttpClient): AiClient = when (config.type) {
        AiProviderType.OpenAiCompatible -> OpenAiCompatibleClient(config, apiKey, client)
        AiProviderType.Gemini -> GeminiClient(config, apiKey, client)
        AiProviderType.Anthropic -> AnthropicClient(config, apiKey, client)
    }
}

object AiJson {
    private val json = Json { isLenient = true }

    /**
     * Models often wrap JSON in ```json fences, add a sentence around it, or answer twice
     * ("Wait, correcting the boxes: {...}"). Returns the last complete top-level `{...}` or `[...]`
     * block that is valid JSON (a correction comes last), else the outermost block, else `null`.
     */
    fun extract(text: String): String? {
        val blocks = topLevelBlocks(text)
        blocks.lastOrNull { block -> runCatching { json.parseToJsonElement(block) }.isSuccess }?.let { return it }
        val start = text.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) return null
        val close = if (text[start] == '{') '}' else ']'
        val end = text.lastIndexOf(close)
        if (end <= start) return null
        return text.substring(start, end + 1)
    }

    /** Balanced `{...}` / `[...]` blocks outside of JSON strings, in order. */
    private fun topLevelBlocks(text: String): List<String> {
        val blocks = mutableListOf<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        text.forEachIndexed { i, c ->
            when {
                inString -> when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                c == '"' && depth > 0 -> inString = true
                c == '{' || c == '[' -> {
                    if (depth == 0) start = i
                    depth++
                }
                (c == '}' || c == ']') && depth > 0 -> {
                    depth--
                    if (depth == 0) blocks += text.substring(start, i + 1)
                }
            }
        }
        return blocks
    }
}
