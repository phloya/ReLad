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
    fun `Gemini blocked prompt is a readable error`() = runTest {
        val server = FakeServer(body = """{"promptFeedback":{"blockReason":"SAFETY"}}""")
        val client = AiClients.create(config(AiProviderType.Gemini), "g", server.client)

        val error = shouldThrow<AiException> { client.send(request) }

        error.kind shouldBe AiException.Kind.BadResponse
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
    fun `Anthropic cannot return images`() = runTest {
        val client = AiClients.create(config(AiProviderType.Anthropic), "a", FakeServer(body = "{}").client)

        shouldThrow<AiException> { client.send(AiRequest(null, "p", imageOutput = true)) }
            .kind shouldBe AiException.Kind.Unsupported
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
    fun `JSON is found inside fences and chatter`() {
        AiJson.extract("Sure!\n```json\n{\"a\":[1,2]}\n```") shouldBe """{"a":[1,2]}"""
        AiJson.extract("[1, 2]") shouldBe "[1, 2]"
        AiJson.extract("no json here") shouldBe null
    }
}
