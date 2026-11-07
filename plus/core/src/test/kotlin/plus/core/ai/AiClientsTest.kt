package plus.core.ai

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Test
import java.util.Base64

class AiClientsTest {

    /** Answers every call with [body] and remembers the request, instead of going to the network. */
    private class FakeServer(private val code: Int = 200, private val body: String) {
        lateinit var request: Request
        val json: JsonObject
            get() = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject

        val client: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                request = chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("fake")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
    }

    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
    private val pngBase64 = Base64.getEncoder().encodeToString(png)

    private fun config(type: AiProviderType, baseUrl: String = type.defaultBaseUrl) =
        AiProviderConfig(id = "p1", name = "Test", type = type, baseUrl = baseUrl, model = "model-x")

    private val request = AiRequest(
        system = "You translate manga.",
        prompt = "Translate this page.",
        images = listOf(AiImage(png, "image/png")),
    )

    @Test
    fun `OpenAI-compatible request and text answer`() = runTest {
        val server = FakeServer(body = """{"choices":[{"message":{"role":"assistant","content":"hello"}}]}""")
        val client = AiClients.create(config(AiProviderType.OpenAiCompatible), "sk-test", server.client)

        val response = client.send(request)

        response.text shouldBe "hello"
        server.request.url.toString() shouldBe "https://openrouter.ai/api/v1/chat/completions"
        server.request.header("Authorization") shouldBe "Bearer sk-test"
        val messages = server.json["messages"]!!.jsonArray
        messages[0].jsonObject["content"]!!.jsonPrimitive.content shouldBe "You translate manga."
        val parts = messages[1].jsonObject["content"]!!.jsonArray
        parts[0].jsonObject["text"]!!.jsonPrimitive.content shouldBe "Translate this page."
        parts[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content shouldBe
            "data:image/png;base64,$pngBase64"
        server.json.containsKey("modalities") shouldBe false
    }

    @Test
    fun `OpenAI-compatible image answer as in OpenRouter`() = runTest {
        val server = FakeServer(
            body = """{"choices":[{"message":{"content":null,
                "images":[{"type":"image_url","image_url":{"url":"data:image/png;base64,$pngBase64"}}]}}]}""",
        )
        val client = AiClients.create(config(AiProviderType.OpenAiCompatible), "sk", server.client)

        val response = client.send(AiRequest(system = null, prompt = "Colorize", imageOutput = true))

        response.images shouldHaveSize 1
        response.images[0].bytes.toList() shouldBe png.toList()
        response.images[0].mimeType shouldBe "image/png"
        server.json["modalities"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("image", "text")
    }

    @Test
    fun `custom base URL keeps its path`() = runTest {
        val server = FakeServer(
            body = """{"choices":[{"message":{"content":[{"type":"text","text":"a"},{"type":"text","text":"b"}]}}]}""",
        )
        val client = AiClients.create(
            config(AiProviderType.OpenAiCompatible, baseUrl = "http://192.168.1.5:1234/v1/"),
            "k",
            server.client,
        )

        client.send(request).text shouldBe "ab"
        server.request.url.toString() shouldBe "http://192.168.1.5:1234/v1/chat/completions"
    }

    @Test
    fun `Gemini request skips thinking parts and reads images`() = runTest {
        val server = FakeServer(
            body = """{"candidates":[{"content":{"parts":[
                {"text":"thinking...","thought":true},
                {"text":"{\"ok\":true}"},
                {"inlineData":{"mimeType":"image/png","data":"$pngBase64"}}
            ]},"finishReason":"STOP"}]}""",
        )
        val client = AiClients.create(config(AiProviderType.Gemini), "g-key", server.client)

        val response = client.send(AiRequest(system = "sys", prompt = "p", images = request.images, jsonOutput = true))

        response.text shouldBe """{"ok":true}"""
        response.images shouldHaveSize 1
        server.request.url.toString() shouldBe
            "https://generativelanguage.googleapis.com/v1beta/models/model-x:generateContent"
        server.request.header("x-goog-api-key") shouldBe "g-key"
        server.json["generationConfig"]!!.jsonObject["responseMimeType"]!!.jsonPrimitive.content shouldBe
            "application/json"
        val parts = server.json["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        parts[0].jsonObject["inlineData"]!!.jsonObject["data"]!!.jsonPrimitive.content shouldBe pngBase64
    }

    @Test
    fun `Gemini image output asks for images and the wanted shape`() = runTest {
        val server = FakeServer(
            body = """{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"image/png","data":"AQID"}}]}}]}""",
        )
        val client = AiClients.create(config(AiProviderType.Gemini), "g", server.client)

        val answer = client.send(
            AiRequest(
                null,
                "Colorize.",
                listOf(AiImage(png, "image/png")),
                imageOutput = true,
                imageAspectRatio = "2:3",
            ),
        )

        answer.images.single().bytes.toList() shouldBe listOf<Byte>(1, 2, 3)
        val config = server.json["generationConfig"]!!.jsonObject
        config["responseModalities"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("TEXT", "IMAGE")
        config["imageConfig"]!!.jsonObject["aspectRatio"]!!.jsonPrimitive.content shouldBe "2:3"
    }

    @Test
    fun `Gemini blocked prompt is a readable error`() = runTest {
        val server = FakeServer(body = """{"promptFeedback":{"blockReason":"SAFETY"}}""")
        val client = AiClients.create(config(AiProviderType.Gemini), "g", server.client)

        val error = shouldThrow<AiException> { client.send(request) }

        error.kind shouldBe AiException.Kind.Refused
        error.message!! shouldContain "SAFETY"
    }

    @Test
    fun `Anthropic request format`() = runTest {
        val server = FakeServer(body = """{"content":[{"type":"text","text":"translated"}],"stop_reason":"end_turn"}""")
        val client = AiClients.create(config(AiProviderType.Anthropic), "a-key", server.client)

        client.send(request).text shouldBe "translated"
        server.request.url.toString() shouldBe "https://api.anthropic.com/v1/messages"
        server.request.header("x-api-key") shouldBe "a-key"
        server.request.header("anthropic-version") shouldBe AnthropicClient.API_VERSION
        server.json["system"]!!.jsonPrimitive.content shouldBe "You translate manga."
        val content = server.json["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        content[0].jsonObject["source"]!!.jsonObject["media_type"]!!.jsonPrimitive.content shouldBe "image/png"
    }

    @Test
    fun `Claude models that can decline get Anthropic's default fallback, others and proxies do not`() = runTest {
        val ok = """{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}"""
        fun anthropic(model: String, baseUrl: String = AiProviderType.Anthropic.defaultBaseUrl) =
            config(AiProviderType.Anthropic, baseUrl).copy(model = model)

        val opus = FakeServer(body = ok)
        AiClients.create(anthropic("claude-opus-5-5"), "k", opus.client).send(request)
        opus.request.header("anthropic-beta") shouldBe AnthropicClient.FALLBACK_BETA
        opus.json["fallbacks"]!!.jsonPrimitive.content shouldBe "default"

        val haiku = FakeServer(body = ok)
        AiClients.create(anthropic("claude-haiku-5-5"), "k", haiku.client).send(request)
        haiku.request.header("anthropic-beta") shouldBe null
        haiku.json.containsKey("fallbacks") shouldBe false

        val proxy = FakeServer(body = ok)
        AiClients.create(anthropic("claude-opus-5-5", "https://proxy.example.com"), "k", proxy.client).send(request)
        proxy.json.containsKey("fallbacks") shouldBe false
    }

    @Test
    fun `a refusal is reported as such`() = runTest {
        val server = FakeServer(
            body = """{"content":[],"stop_reason":"refusal","stop_details":{"type":"refusal","category":"cyber"}}""",
        )
        val client = AiClients.create(config(AiProviderType.Anthropic), "k", server.client)

        val error = shouldThrow<AiException> { client.send(request) }

        error.kind shouldBe AiException.Kind.Refused
        error.message!! shouldContain "declined"
        error.message!! shouldContain "cyber"
    }

    @Test
    fun `Anthropic cannot return images`() = runTest {
        val client = AiClients.create(config(AiProviderType.Anthropic), "a", FakeServer(body = "{}").client)

        shouldThrow<AiException> { client.send(AiRequest(null, "p", imageOutput = true)) }
            .kind shouldBe AiException.Kind.Unsupported
    }

    @Test
    fun `the fixed beginning of the system prompt is marked for caching on Claude and joined elsewhere`() = runTest {
        val cached = AiRequest(system = "This title's glossary", prompt = "p", stableSystem = "Fixed rules")

        val anthropic = FakeServer(body = """{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}""")
        AiClients.create(config(AiProviderType.Anthropic), "k", anthropic.client).send(cached)
        val blocks = anthropic.json["system"]!!.jsonArray.map { it.jsonObject }
        blocks.map { it["text"]!!.jsonPrimitive.content } shouldBe listOf("Fixed rules", "This title's glossary")
        blocks.forEach { it["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content shouldBe "ephemeral" }

        val openAi = FakeServer(body = """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}""")
        AiClients.create(config(AiProviderType.OpenAiCompatible), "k", openAi.client).send(cached)
        openAi.json["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content shouldBe
            "Fixed rules\n\nThis title's glossary"

        val gemini = FakeServer(body = """{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}""")
        AiClients.create(config(AiProviderType.Gemini), "k", gemini.client).send(cached)
        gemini.json["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!
            .jsonPrimitive.content shouldBe "Fixed rules\n\nThis title's glossary"
    }

    @Test
    fun `low reasoning is asked for only where the model takes it`() = runTest {
        val low = AiRequest(system = null, prompt = "p", effort = AiEffort.Low)
        val claudeAnswer = """{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}"""
        val geminiAnswer = """{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}"""

        val sonnet = FakeServer(body = claudeAnswer)
        AiClients.create(config(AiProviderType.Anthropic).copy(model = "claude-sonnet-5-5"), "k", sonnet.client)
            .send(low)
        sonnet.json["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content shouldBe "low"

        val oldClaude = FakeServer(body = claudeAnswer)
        AiClients.create(config(AiProviderType.Anthropic).copy(model = "claude-haiku-4-5"), "k", oldClaude.client)
            .send(low)
        oldClaude.json.containsKey("output_config") shouldBe false

        val gemini3 = FakeServer(body = geminiAnswer)
        AiClients.create(config(AiProviderType.Gemini).copy(model = "gemini-3.1-flash-lite"), "k", gemini3.client)
            .send(low)
        gemini3.json["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingLevel"]!!
            .jsonPrimitive.content shouldBe "low"

        val gemini25 = FakeServer(body = geminiAnswer)
        AiClients.create(config(AiProviderType.Gemini).copy(model = "gemini-2.5-flash-lite"), "k", gemini25.client)
            .send(low)
        gemini25.json["generationConfig"]!!.jsonObject.containsKey("thinkingConfig") shouldBe false
    }

    @Test
    fun `OpenRouter is asked for low reasoning, other OpenAI-format servers are not`() = runTest {
        val low = AiRequest(system = null, prompt = "p", effort = AiEffort.Low)
        val answer = """{"choices":[{"message":{"content":"ok"}}]}"""

        val openRouter = FakeServer(body = answer)
        AiClients.create(config(AiProviderType.OpenAiCompatible), "k", openRouter.client).send(low)
        openRouter.json["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content shouldBe "low"

        val local = FakeServer(body = answer)
        AiClients.create(
            config(AiProviderType.OpenAiCompatible, "http://10.0.2.2:8765/v1"),
            "k",
            local.client,
        ).send(low)
        local.json.containsKey("reasoning") shouldBe false
    }

    @Test
    fun `too many requests are asked again after a wait`() = runTest {
        var calls = 0
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                calls++
                val busy = calls <= 2
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(if (busy) 429 else 200)
                    .message("fake")
                    .apply { if (busy) header("Retry-After", "1") }
                    .body(
                        (if (busy) """{"error":{"message":"slow down"}}""" else ANSWER_OK)
                            .toResponseBody("application/json".toMediaType()),
                    )
                    .build()
            }
            .build()

        val answer = AiClients.create(config(AiProviderType.OpenAiCompatible), "k", client).send(request)

        answer.text shouldBe "ok"
        calls shouldBe 3
    }

    @Test
    fun `token usage is read from all three formats`() = runTest {
        val anthropic = FakeServer(
            body = """{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn",
                "usage":{"input_tokens":100,"cache_creation_input_tokens":2000,
                "cache_read_input_tokens":3000,"output_tokens":400}}""",
        )
        AiClients.create(config(AiProviderType.Anthropic), "k", anthropic.client).send(request).usage shouldBe
            AiUsage(inputTokens = 5100, cachedInputTokens = 3000, cacheWriteTokens = 2000, outputTokens = 400)

        val gemini = FakeServer(
            body = """{"candidates":[{"content":{"parts":[{"text":"ok"}]}}],
                "usageMetadata":{"promptTokenCount":1800,"cachedContentTokenCount":1000,
                "candidatesTokenCount":300,"thoughtsTokenCount":200}}""",
        )
        AiClients.create(config(AiProviderType.Gemini), "k", gemini.client).send(request).usage shouldBe
            AiUsage(inputTokens = 1800, cachedInputTokens = 1000, outputTokens = 500)

        val openRouter = FakeServer(
            body = """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}],
                "usage":{"prompt_tokens":1500,"completion_tokens":350,
                "prompt_tokens_details":{"cached_tokens":1024},"cost":0.0021}}""",
        )
        AiClients.create(config(AiProviderType.OpenAiCompatible), "k", openRouter.client).send(request).usage shouldBe
            AiUsage(inputTokens = 1500, cachedInputTokens = 1024, outputTokens = 350, reportedCostUsd = 0.0021)
    }

    @Test
    fun `missing key fails before any request`() = runTest {
        val client = AiClients.create(config(AiProviderType.OpenAiCompatible), null, FakeServer(body = "{}").client)

        shouldThrow<AiException> { client.send(request) }.kind shouldBe AiException.Kind.NoKey
    }

    @Test
    fun `HTTP errors carry the provider message`() = runTest {
        val server = FakeServer(code = 401, body = """{"error":{"message":"Invalid API key"}}""")
        val client = AiClients.create(config(AiProviderType.OpenAiCompatible), "bad", server.client)

        val error = shouldThrow<AiException> { client.send(request) }

        error.kind shouldBe AiException.Kind.Http
        error.message!! shouldContain "401"
        error.message!! shouldContain "Invalid API key"
    }

    @Test
    fun `when the model answers twice, its correction wins`() {
        val answer = """{"regions":[{"box":[1,2,3,4],"text":"a {b}"}]}

            Wait, correcting the box coordinates:

            {"regions":[{"box":[2,1,4,3],"text":"a {b}"}]}"""

        AiJson.extract(answer) shouldBe """{"regions":[{"box":[2,1,4,3],"text":"a {b}"}]}"""
        AiJson.extract("""Here: {"a": "}"} and that's all""") shouldBe """{"a": "}"}"""
    }

    @Test
    fun `JSON is found inside fences and chatter`() {
        AiJson.extract("Sure!\n```json\n{\"a\":[1,2]}\n```") shouldBe """{"a":[1,2]}"""
        AiJson.extract("[1, 2]") shouldBe "[1, 2]"
        AiJson.extract("no json here") shouldBe null
    }

    private companion object {
        const val ANSWER_OK = """{"choices":[{"message":{"content":"ok"}}]}"""
    }
}
