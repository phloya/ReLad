package plus.enhance

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class ModelFiles(val param: File, val weights: File)

/**
 * Downloads models on first use (the APK carries none) into the app's files folder and checks
 * every file's SHA-256. A file that does not match is deleted, never loaded.
 */
class ModelStore(private val dir: File, private val client: OkHttpClient) {

    class ModelDownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

    private val mutex = Mutex()

    fun isDownloaded(model: EnhanceModel): Boolean =
        file(model, model.param).isFile && file(model, model.weights).isFile

    /** Returns the model's files, downloading them first if needed. */
    suspend fun get(model: EnhanceModel): ModelFiles = mutex.withLock {
        withContext(Dispatchers.IO) {
            ModelFiles(fetch(model, model.param), fetch(model, model.weights))
        }
    }

    fun delete(model: EnhanceModel) {
        File(dir, model.id).deleteRecursively()
    }

    fun sizeBytes(): Long = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    private fun file(model: EnhanceModel, modelFile: ModelFile) =
        File(File(dir, model.id), modelFile.url.substringAfterLast('/'))

    private fun fetch(model: EnhanceModel, modelFile: ModelFile): File {
        val target = file(model, modelFile)
        if (target.isFile) return target
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".part")
        try {
            client.newCall(Request.Builder().url(modelFile.url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw ModelDownloadException("HTTP ${response.code} for ${modelFile.url}")
                }
                tmp.outputStream().use { response.body.byteStream().copyTo(it) }
            }
        } catch (e: IOException) {
            tmp.delete()
            throw e as? ModelDownloadException ?: ModelDownloadException("Download failed: ${e.message}", e)
        }
        val actual = sha256(tmp)
        if (actual != modelFile.sha256) {
            tmp.delete()
            throw ModelDownloadException("File ${target.name} is not the expected one (hash $actual)")
        }
        if (!tmp.renameTo(target)) throw ModelDownloadException("Could not save ${target.name}")
        return target
    }

    companion object {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
