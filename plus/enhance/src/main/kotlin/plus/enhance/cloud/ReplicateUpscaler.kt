package plus.enhance.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import plus.core.ai.AiException
import java.io.IOException
import java.util.Base64

/**
 * Upscales a page with a model on Replicate, through its official HTTP API
 * (https://replicate.com/docs/reference/http): upload the page as a file, start a prediction
 * that waits up to a minute for the result, poll if it takes longer, download the output.
 */
class ReplicateUpscaler(
    private val client: OkHttpClient,
    private val token: String,
    baseUrl: String = DEFAULT_BASE_URL,
    private val pollIntervalMs: Long = 2_000,
    private val timeoutMs: Long = 5 * 60_000,
) {
    private val base = baseUrl.trimEnd('/')
    private val apiHost = base.toHttpUrl().host
    private val json = Json { ignoreUnknownKeys = true }

    /** The account the token belongs to: a free call to check the token. */
    suspend fun account(): String = withContext(Dispatchers.IO) {
        val body = call(authorized("$base/account").get().build())
        body.jsonObject["username"]?.jsonPrimitive?.contentOrNull ?: "?"
    }

    suspend fun upscale(image: ByteArray, mimeType: String, model: ReplicateModel): ByteArray =
        withContext(Dispatchers.IO) {
            val file = upload(image, mimeType)
            try {
                val output = run(model, file.url)
                download(output)
            } finally {
                runCatching { execute(authorized("$base/files/${file.id}").delete().build()).close() }
            }
        }

    private class UploadedFile(val id: String, val url: String)

    private fun upload(image: ByteArray, mimeType: String): UploadedFile {
        val extension = if (mimeType == "image/png") "png" else "jpg"
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("content", "page.$extension", image.toRequestBody(mimeType.toMediaType()))
            .build()
        val answer = call(authorized("$base/files").post(body).build()).jsonObject
        val id = answer["id"]?.jsonPrimitive?.contentOrNull
        val url = answer["urls"]?.jsonObject?.get("get")?.jsonPrimitive?.contentOrNull
        if (id == null ||
            url == null
        ) {
            throw AiException("Replicate did not return the uploaded file", AiException.Kind.BadResponse)
        }
        return UploadedFile(id, url)
    }

    /** Starts the prediction and waits for it; returns the output URL (or data URI). */
    private suspend fun run(model: ReplicateModel, imageUrl: String): String {
        val input = buildJsonObject {
            parseInput(model.extraInput).forEach { (key, value) -> put(key, value) }
            put(model.imageField, imageUrl)
        }
        var prediction = start(model.model, input)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (prediction.status !in FINISHED) {
            if (System.currentTimeMillis() > deadline) {
                prediction.cancelUrl?.let { runCatching { execute(authorized(it).post(EMPTY).build()).close() } }
                throw AiException("Replicate took longer than ${timeoutMs / 60_000} min", AiException.Kind.Network)
            }
            delay(pollIntervalMs)
            val url = prediction.getUrl?.takeIf { it.toHttpUrlOrNull()?.host == apiHost }
                ?: throw AiException("Replicate gave no status address", AiException.Kind.BadResponse)
            prediction = Prediction.of(call(authorized(url).get().build()))
        }
        if (prediction.status != "succeeded") {
            throw AiException(
                "Replicate: ${prediction.status}" + (prediction.error?.let { ": $it" } ?: ""),
                AiException.Kind.BadResponse,
            )
        }
        return prediction.output ?: throw AiException("Replicate returned no image", AiException.Kind.BadResponse)
    }

    private fun start(model: String, input: JsonObject): Prediction {
        val version = model.substringAfter(':', "")
        if (version.isNotEmpty()) {
            return create(
                "$base/predictions",
                buildJsonObject {
                    put("version", version)
                    put("input", input)
                },
            )
        }
        // Official models run by name; community models need their latest version
        val (owner, name) = model.split('/', limit = 2).takeIf { it.size == 2 }
            ?: throw AiException("Model must be owner/name or owner/name:version: $model", AiException.Kind.BadResponse)
        return try {
            create("$base/models/$owner/$name/predictions", buildJsonObject { put("input", input) }, raw = true)
        } catch (e: HttpError) {
            if (e.code != 404) throw e.toAiException()
            val info = call(authorized("$base/models/$owner/$name").get().build()).jsonObject
            val latest = info["latest_version"]?.let { it as? JsonObject }?.get("id")?.jsonPrimitive?.contentOrNull
                ?: throw AiException("Replicate model $model has no version", AiException.Kind.BadResponse)
            create(
                "$base/predictions",
                buildJsonObject {
                    put("version", latest)
                    put("input", input)
                },
            )
        }
    }

    /** Creates a prediction; with [raw], HTTP errors stay [HttpError] so the caller can react to the code. */
    private fun create(url: String, body: JsonObject, raw: Boolean = false): Prediction {
        val request = authorized(url)
            .header("Prefer", "wait=60")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        return Prediction.of(if (raw) callRaw(request) else call(request))
    }

    private fun download(output: String): ByteArray {
        if (output.startsWith("data:")) {
            return Base64.getDecoder().decode(output.substringAfter(','))
        }
        val url = output.toHttpUrlOrNull() ?: throw AiException("Bad output address", AiException.Kind.BadResponse)
        // The token goes only to Replicate itself and its file host, never to another server
        val trusted =
            url.host == apiHost || url.host == "replicate.delivery" || url.host.endsWith(".replicate.delivery")
        val request = (if (trusted) authorized(output) else Request.Builder().url(output)).get().build()
        execute(request).use { response ->
            if (!response.isSuccessful) {
                throw AiException(
                    "Download failed: HTTP ${response.code}",
                    AiException.Kind.Http,
                )
            }
            return response.body.bytes()
        }
    }

    private fun authorized(url: String) = Request.Builder().url(url).header("Authorization", "Bearer $token")

    private class HttpError(val code: Int, val detail: String) : IOException("HTTP $code: $detail") {
        fun toAiException() = AiException("Replicate: HTTP $code $detail".trim(), AiException.Kind.Http, this)
    }

    private fun call(request: Request): JsonElement = try {
        callRaw(request)
    } catch (e: HttpError) {
        throw e.toAiException()
    }

    /** Like [call], but HTTP errors stay [HttpError] so the caller can react to the code. */
    private fun callRaw(request: Request): JsonElement = execute(request).use { response ->
        val text = response.body.string()
        if (!response.isSuccessful) throw HttpError(response.code, errorDetail(text))
        try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw AiException("Replicate answered with something that is not JSON", AiException.Kind.BadResponse, e)
        }
    }

    private fun execute(request: Request): Response = try {
        client.newCall(request).execute()
    } catch (e: IOException) {
        throw AiException("No connection to Replicate: ${e.message}", AiException.Kind.Network, e)
    }

    private fun errorDetail(text: String): String = runCatching {
        val obj = json.parseToJsonElement(text).jsonObject
        (obj["detail"] ?: obj["title"])?.jsonPrimitive?.contentOrNull
    }.getOrNull() ?: text.take(200)

    private fun parseInput(text: String): JsonObject = try {
        json.parseToJsonElement(text.ifBlank { "{}" }).jsonObject
    } catch (e: Exception) {
        throw AiException("Model inputs are not a JSON object: $text", AiException.Kind.BadResponse, e)
    }

    private class Prediction(
        val status: String,
        val output: String?,
        val error: String?,
        val getUrl: String?,
        val cancelUrl: String?,
    ) {
        companion object {
            fun of(element: JsonElement): Prediction {
                val obj = element.jsonObject
                val urls = obj["urls"] as? JsonObject
                return Prediction(
                    status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "unknown",
                    output = outputUrl(obj["output"]),
                    error = (obj["error"] as? JsonPrimitive)?.contentOrNull,
                    getUrl = (urls?.get("get") as? JsonPrimitive)?.contentOrNull,
                    cancelUrl = (urls?.get("cancel") as? JsonPrimitive)?.contentOrNull,
                )
            }

            /** Output is a URL, a list of URLs, or a data URI, depending on the model. */
            private fun outputUrl(output: JsonElement?): String? = when (output) {
                is JsonPrimitive -> output.contentOrNull
                is JsonArray -> output.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull }
                else -> null
            }
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.replicate.com/v1"
        private val FINISHED = setOf("succeeded", "failed", "canceled", "aborted")
        private val JSON_TYPE = "application/json".toMediaType()
        private val EMPTY: RequestBody = ByteArray(0).toRequestBody(null)
    }
}
