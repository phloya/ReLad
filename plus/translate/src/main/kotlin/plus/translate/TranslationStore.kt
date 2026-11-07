package plus.translate

import java.io.File
import java.security.MessageDigest

/**
 * The translations themselves (each page's text, boxes and usage), kept for good in app files.
 * The translated image is only a cache: it can be drawn again from the original and this text at
 * no cost and offline. So a downloaded chapter has one copy of its pictures (the download) and a
 * few kilobytes of translation per page, which neither the cache's size limit nor Android's
 * clearing of caches throws away, and a change in lettering doesn't pay for the model again.
 */
class TranslationStore(private val dir: File) {

    private val lock = Any()

    fun get(key: String): String? = synchronized(lock) {
        val file = file(key)
        if (!file.isFile) return null
        runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun put(key: String, translation: String) = synchronized(lock) {
        dir.mkdirs()
        // A crash never leaves half a file behind
        val tmp = File(dir, "$key.tmp")
        tmp.writeText(translation)
        file(key).delete()
        tmp.renameTo(file(key))
    }

    fun sizeBytes(): Long = synchronized(lock) { dir.listFiles()?.sumOf { it.length() } ?: 0L }

    fun count(): Int = synchronized(lock) { dir.listFiles()?.count { it.name.endsWith(SUFFIX) } ?: 0 }

    /** Forgets every translation: pages are translated again (by the model) when read. */
    fun clear() = synchronized(lock) { dir.listFiles()?.forEach { it.delete() } }

    private fun file(key: String) = File(dir, key + SUFFIX)

    companion object {
        private const val SUFFIX = ".json"

        /** A page's key: its image and everything that changes the text (not how it is drawn). */
        fun key(image: ByteArray, signature: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(image)
            digest.update(0)
            digest.update(signature.toByteArray())
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
