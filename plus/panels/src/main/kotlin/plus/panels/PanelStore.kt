package plus.panels

import plus.core.pipeline.PageProcessingEngine
import java.io.File

/**
 * Remembers what the detector found, by the image's hash: in memory for the pages around the
 * current one, on disk (a small JSON file per page) for when a chapter is opened again.
 *
 * Not part of the page pipeline's cache on purpose: the pipeline stores images, and a panel step
 * there would store a second copy of every page and reload pages on screen just to add panels.
 */
class PanelStore(
    private val dir: File,
    private val maxFiles: Int = DEFAULT_MAX_FILES,
) {

    private val lock = Any()

    private val memory = object : LinkedHashMap<String, PageLayout>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PageLayout>?) = size > MEMORY_ENTRIES
    }

    /** The layout of [image] (encoded bytes), from the cache or found now by [detect]. */
    fun layout(image: ByteArray, detect: (ByteArray) -> PageLayout): PageLayout {
        val key = key(image)
        get(key)?.let { return it }
        val layout = detect(image)
        put(key, layout)
        return layout
    }

    fun get(key: String): PageLayout? = synchronized(lock) {
        memory[key]?.let { return it }
        val file = file(key)
        if (!file.isFile) return null
        file.setLastModified(System.currentTimeMillis())
        val layout = runCatching { PageLayout.fromJson(file.readText()) }.getOrNull() ?: return null
        memory[key] = layout
        layout
    }

    fun put(key: String, layout: PageLayout) = synchronized(lock) {
        memory[key] = layout
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "$key.tmp")
            tmp.writeText(layout.toJson())
            tmp.renameTo(file(key))
        }
        trim()
    }

    fun clear() = synchronized(lock) {
        memory.clear()
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun trim() {
        val files = dir.listFiles()?.filter { it.name.endsWith(SUFFIX) } ?: return
        if (files.size <= maxFiles) return
        files.sortedBy { it.lastModified() }.take(files.size - maxFiles).forEach { it.delete() }
    }

    private fun file(key: String) = File(dir, key + SUFFIX)

    companion object {
        const val DEFAULT_MAX_FILES = 3000
        private const val MEMORY_ENTRIES = 48
        private const val SUFFIX = ".json"

        fun key(image: ByteArray): String = PageProcessingEngine.sha256(image) + "-v" + PanelDetector.VERSION
    }
}
