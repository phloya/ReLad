package plus.enhance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest

class ModelStoreTest {

    @TempDir
    lateinit var dir: File

    private var requests = 0

    /** Serves [files] by URL instead of the network. */
    private fun client(files: Map<String, ByteArray>) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            requests++
            val body = files[chain.request().url.toString()]
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(if (body != null) 200 else 404)
                .message("fake")
                .body((body ?: ByteArray(0)).toResponseBody("application/octet-stream".toMediaType()))
                .build()
        }
        .build()

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        "%02x".format(it)
    }

    private val paramBytes = "7767517 param".toByteArray()
    private val weightBytes = ByteArray(1000) { it.toByte() }

    private fun model(weightsSha: String = sha(weightBytes)) = EnhanceModel(
        id = "test-model",
        name = "Test",
        scale = 2,
        prepadding = 18,
        param = ModelFile("https://example.com/m.param", sha(paramBytes), paramBytes.size.toLong()),
        weights = ModelFile("https://example.com/m.bin", weightsSha, weightBytes.size.toLong()),
        license = ModelLicense("MIT", "https://example.com", commercialUse = true),
    )

    private val served = mapOf("https://example.com/m.param" to paramBytes, "https://example.com/m.bin" to weightBytes)

    @Test
    fun `downloads once and keeps the verified files`() = runTest {
        val store = ModelStore(dir, client(served))

        val files = store.get(model())
        store.get(model())

        files.weights.readBytes().toList() shouldBe weightBytes.toList()
        store.isDownloaded(model()) shouldBe true
        requests shouldBe 2
    }

    @Test
    fun `a file with the wrong hash is rejected and not kept`() = runTest {
        val store = ModelStore(dir, client(served))
        val wrong = model(weightsSha = "0".repeat(64))

        shouldThrow<ModelStore.ModelDownloadException> { store.get(wrong) }

        store.isDownloaded(wrong) shouldBe false
        dir.walk().none { it.name.endsWith(".bin") || it.name.endsWith(".part") } shouldBe true
    }

    @Test
    fun `HTTP errors are reported`() = runTest {
        val store = ModelStore(dir, client(emptyMap()))

        shouldThrow<ModelStore.ModelDownloadException> { store.get(model()) }
    }
}
