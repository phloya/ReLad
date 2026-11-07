package plus.core.pipeline

import java.io.File

/**
 * Disk cache for processed pages: `<key>.img` holds the image, `<key>.json` optional metadata.
 * When the folder grows past [maxBytes], the least recently used entries are deleted.
 */
class PageCache(
    private val dir: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {

    private val lock = Any()

    fun get(key: String): ByteArray? = synchronized(lock) {
        val file = imageFile(key)
        if (!file.isFile) return null
        file.setLastModified(System.currentTimeMillis())
        runCatching { file.readBytes() }.getOrNull()
    }

    fun contains(key: String): Boolean = synchronized(lock) { imageFile(key).isFile }

    fun metadata(key: String): String? = synchronized(lock) {
        metadataFile(key).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
    }

    fun put(key: String, page: ProcessedPage) = synchronized(lock) {
        dir.mkdirs()
        // Write to a temp file first so a crash never leaves a half-written image behind
        val tmp = File(dir, "$key.tmp")
        tmp.writeBytes(page.image)
        if (page.metadata != null) {
            metadataFile(key).writeText(page.metadata)
        } else {
            metadataFile(key).delete()
        }
        imageFile(key).delete()
        tmp.renameTo(imageFile(key))
        trim()
    }

    fun remove(key: String) = synchronized(lock) {
        imageFile(key).delete()
        metadataFile(key).delete()
    }

    fun clear() = synchronized(lock) {
        dir.listFiles()?.forEach { it.delete() }
    }

    fun sizeBytes(): Long = synchronized(lock) {
        dir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    private fun trim() {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        val images = files.filter { it.name.endsWith(IMAGE_SUFFIX) }.sortedBy { it.lastModified() }
        for (image in images) {
            if (total <= maxBytes) break
            val key = image.name.removeSuffix(IMAGE_SUFFIX)
            total -= image.length()
            image.delete()
            val meta = metadataFile(key)
            total -= meta.length()
            meta.delete()
        }
    }

    private fun imageFile(key: String) = File(dir, key + IMAGE_SUFFIX)
    private fun metadataFile(key: String) = File(dir, "$key.json")

    companion object {
        const val DEFAULT_MAX_BYTES = 300L * 1024 * 1024
        private const val IMAGE_SUFFIX = ".img"
    }
}
