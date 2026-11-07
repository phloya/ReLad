package plus.enhance.cloud

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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
import plus.core.ai.AiException
import java.util.Base64

class ReplicateUpscalerTest {

    private class Call(val method: String, val url: String, val auth: String?, val body: String)

    /** Answers requests from [routes] (method + URL → status and body) and records them. */
    private class FakeReplicate(val routes: (Request) -> Pair<Int, ByteArray>) {
        val calls = mutableListOf<Call>()
        val client: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = request.body?.let { Buffer().also(it::writeTo).readUtf8() }.orEmpty()
            calls += Call(request.method, request.url.toString(), request.header("Authorization"), body)
            val (code, bytes) = routes(request)
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("fake")
                .body(bytes.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
    }

    private val page = byteArrayOf(1, 2, 3)
    private val result = byteArrayOf(9, 8, 7, 6)

    private fun json(text: String) = 200 to text.toByteArray()

    private val upload = """{"id":"f1","urls":{"get":"https://api.replicate.com/v1/files/f1"}}"""

    private fun prediction(status: String, output: String? = null, error: String? = null) = """
        {"id":"p1","status":"$status",
         "output":${output?.let { "\"$it\"" } ?: "null"},
         "error":${error?.let { "\"$it\"" } ?: "null"},
         "urls":{"get":"https://api.replicate.com/v1/predictions/p1","cancel":"https://api.replicate.com/v1/predictions/p1/cancel"}}
    """.trimIndent()

    @Test
    fun `official model runs by name and the result is downloaded`() = runTest {
        val fake = FakeReplicate { request ->
            when (request.method + " " + request.url.encodedPath) {
                "POST /v1/files" -> json(upload)
                "POST /v1/models/topazlabs/image-upscale/predictions" ->
                    201 to prediction("succeeded", "https://replicate.delivery/out.png").toByteArray()
                "GET /out.png" -> 200 to result
                "DELETE /v1/files/f1" -> 204 to ByteArray(0)
                else -> 404 to "{}".toByteArray()
            }
        }
        val upscaler = ReplicateUpscaler(fake.client, "r8_token")

        upscaler.upscale(page, "image/jpeg", ReplicateModels.topaz).toList() shouldBe result.toList()

        fake.calls.map { "${it.method} ${it.url}" } shouldContainExactly listOf(
            "POST https://api.replicate.com/v1/files",
            "POST https://api.replicate.com/v1/models/topazlabs/image-upscale/predictions",
            "GET https://replicate.delivery/out.png",
            "DELETE https://api.replicate.com/v1/files/f1",
        )
        fake.calls.all { it.auth == "Bearer r8_token" } shouldBe true
        val input = Json.parseToJsonElement(fake.calls[1].body).jsonObject["input"]!!.jsonObject
        input["image"]!!.jsonPrimitive.content shouldBe "https://api.replicate.com/v1/files/f1"
        input["upscale_factor"]!!.jsonPrimitive.content shouldBe "2x"
    }

    @Test
    fun `pinned version, slow prediction is polled until it succeeds`() = runTest {
        var polls = 0
        val fake = FakeReplicate { request ->
            when (request.method + " " + request.url.encodedPath) {
                "POST /v1/files" -> json(upload)
                "POST /v1/predictions" -> 202 to prediction("starting").toByteArray()
                "GET /v1/predictions/p1" -> {
                    polls++
                    json(
                        if (polls <
                            3
                        ) {
                            prediction("processing")
                        } else {
                            prediction("succeeded", "https://replicate.delivery/o.jpg")
                        },
                    )
                }
                "GET /o.jpg" -> 200 to result
                else -> 204 to ByteArray(0)
            }
        }
        val upscaler = ReplicateUpscaler(fake.client, "t", pollIntervalMs = 1)

        upscaler.upscale(page, "image/jpeg", ReplicateModels.realEsrgan).toList() shouldBe result.toList()

        polls shouldBe 3
        val body = Json.parseToJsonElement(fake.calls[1].body).jsonObject
        body["version"]!!.jsonPrimitive.content shouldBe ReplicateModels.realEsrgan.model.substringAfter(':')
        body["input"]!!.jsonObject["img"]!!.jsonPrimitive.content shouldBe "https://api.replicate.com/v1/files/f1"
        body["input"]!!.jsonObject["version"]!!.jsonPrimitive.content shouldBe "General - RealESRGANplus"
    }

    @Test
    fun `community model by name falls back to its latest version`() = runTest {
        val fake = FakeReplicate { request ->
            when (request.method + " " + request.url.encodedPath) {
                "POST /v1/files" -> json(upload)
                "POST /v1/models/someone/upscaler/predictions" -> 404 to """{"detail":"Not found"}""".toByteArray()
                "GET /v1/models/someone/upscaler" -> json("""{"latest_version":{"id":"v42"}}""")
                "POST /v1/predictions" ->
                    201 to
                        prediction("succeeded", "https://replicate.delivery/o.png").toByteArray()
                "GET /o.png" -> 200 to result
                else -> 204 to ByteArray(0)
            }
        }
        val model = ReplicateModel("custom", "x", "someone/upscaler", "image", license = "?", price = "?")

        ReplicateUpscaler(fake.client, "t").upscale(page, "image/png", model).toList() shouldBe result.toList()

        val body = Json.parseToJsonElement(fake.calls.first { it.url.endsWith("/v1/predictions") }.body).jsonObject
        body["version"]!!.jsonPrimitive.content shouldBe "v42"
    }

    @Test
    fun `data URI output is decoded without a download`() = runTest {
        val dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(result)
        val fake = FakeReplicate { request ->
            when (request.method + " " + request.url.encodedPath) {
                "POST /v1/files" -> json(upload)
                "POST /v1/models/topazlabs/image-upscale/predictions" ->
                    201 to
                        prediction("succeeded", dataUri).toByteArray()
                else -> 204 to ByteArray(0)
            }
        }

        ReplicateUpscaler(fake.client, "t").upscale(page, "image/jpeg", ReplicateModels.topaz).toList() shouldBe
            result.toList()
    }

    @Test
    fun `the token is never sent to other servers`() = runTest {
        val fake = FakeReplicate { request ->
            when (request.method + " " + request.url.encodedPath) {
                "POST /v1/files" -> json(upload)
                "POST /v1/models/topazlabs/image-upscale/predictions" ->
                    201 to prediction("succeeded", "https://evil.example.com/o.png").toByteArray()
                "GET /o.png" -> 200 to result
                else -> 204 to ByteArray(0)
            }
        }

        ReplicateUpscaler(fake.client, "secret").upscale(page, "image/jpeg", ReplicateModels.topaz)

        fake.calls.single { it.url.startsWith("https://evil.example.com") }.auth shouldBe null
    }

    @Test
    fun `failed prediction and HTTP errors are reported with Replicate's message`() = runTest {
        val failing = FakeReplicate { request ->
            when (request.method + " " + request.url.encodedPath) {
                "POST /v1/files" -> json(upload)
                "POST /v1/models/topazlabs/image-upscale/predictions" ->
                    201 to prediction("failed", error = "CUDA out of memory").toByteArray()
                else -> 204 to ByteArray(0)
            }
        }
        shouldThrow<AiException> {
            ReplicateUpscaler(failing.client, "t").upscale(page, "image/jpeg", ReplicateModels.topaz)
        }.message!! shouldContain "CUDA out of memory"

        val unauthorized = FakeReplicate {
            401 to
                """{"title":"Unauthenticated","detail":"Invalid token."}""".toByteArray()
        }
        val error = shouldThrow<AiException> { ReplicateUpscaler(unauthorized.client, "bad").account() }
        error.kind shouldBe AiException.Kind.Http
        error.message!! shouldContain "Invalid token."
    }

    @Test
    fun `account check returns the user name`() = runTest {
        val fake = FakeReplicate { json("""{"type":"user","username":"phloya"}""") }

        ReplicateUpscaler(fake.client, "t").account() shouldBe "phloya"
        fake.calls.single().url shouldBe "https://api.replicate.com/v1/account"
    }
}
