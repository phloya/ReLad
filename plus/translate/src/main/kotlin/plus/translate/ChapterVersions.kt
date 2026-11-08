package plus.translate

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A chapter's two versions, the original and the translation. A chapter opens in the original and
 * costs nothing until the reader asks to translate it; then it is translated whole, in the
 * background, and opens in the translation (or in the original, if that is the one last chosen).
 *
 * Kept in app files with which pages are translated, so leaving the reader or the app loses
 * nothing: the reader's count went from 50 % back to a few pages, as it only counted the pages it
 * had seen again. The translation is a version of the chapter, not a chapter of its own: Mihon
 * drops chapters its source doesn't list, and read progress and downloads would count twice.
 */
class ChapterVersions(private val file: File) {

    @Serializable
    data class Chapter(
        /** The reader asked for the translation (Translate in the reader or in the chapter list). */
        val translate: Boolean = false,
        /** The original is shown, the translation goes on meanwhile. */
        val showOriginal: Boolean = false,
        /** Pages in the chapter, 0 until it is loaded to be translated. */
        val pages: Int = 0,
        /**
         * Translated pages: index → the text signature they were translated with. Another
         * language or model makes them pages to translate again.
         */
        val done: Map<Int, String> = emptyMap(),
        /**
         * What each page was last drawn with (lettering version included): after an update that
         * draws pages better, the chapter is drawn again whole, not page by page as it is read.
         */
        val drawn: Map<Int, String> = emptyMap(),
    )

    class Progress(val done: Int, val total: Int) {
        val complete: Boolean get() = total in 1..done
    }

    private val lock = Any()
    private var chapters: MutableMap<Long, Chapter>? = null
    private val _changes = MutableStateFlow(0)

    /** Changes with every change, for screens to show the chapters as they are now. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    fun get(chapterId: Long): Chapter? = synchronized(lock) { loaded()[chapterId] }

    fun translates(chapterId: Long): Boolean = get(chapterId)?.translate == true

    /** The reader shows the translation of this chapter. */
    fun showsTranslation(chapterId: Long): Boolean = get(chapterId)?.let { it.translate && !it.showOriginal } == true

    /** Translate [chapterId] and show it translated. */
    fun startTranslating(chapterId: Long) = change(chapterId) { it.copy(translate = true, showOriginal = false) }

    fun showOriginal(chapterId: Long, original: Boolean) = change(chapterId) { it.copy(showOriginal = original) }

    fun pageCount(chapterId: Long, pages: Int) = change(chapterId) { it.copy(pages = pages) }

    /** The page is translated with [signature] and drawn with [drawnWith]. */
    fun pageTranslated(chapterId: Long, pageIndex: Int, signature: String, drawnWith: String? = null) {
        val chapter = get(chapterId)
        if (chapter?.done?.get(pageIndex) == signature &&
            (drawnWith == null || chapter.drawn[pageIndex] == drawnWith)
        ) {
            return
        }
        change(chapterId) {
            it.copy(
                done = it.done + (pageIndex to signature),
                drawn = if (drawnWith == null) it.drawn else it.drawn + (pageIndex to drawnWith),
            )
        }
    }

    /** Translated again: the page counts as not done until the new translation is in. */
    fun pageForgotten(chapterId: Long, pageIndex: Int) =
        change(chapterId) { it.copy(done = it.done - pageIndex, drawn = it.drawn - pageIndex) }

    /** The whole chapter is translated again. */
    fun pagesForgotten(chapterId: Long) = change(chapterId) { it.copy(done = emptyMap(), drawn = emptyMap()) }

    /** Some page of the chapter is not drawn with [drawnWith] (the lettering changed since). */
    fun needsDrawing(chapterId: Long, drawnWith: String, pages: Int? = null): Boolean {
        val chapter = get(chapterId) ?: return false
        val total = pages ?: chapter.pages
        return (0 until total).any { chapter.drawn[it] != drawnWith }
    }

    /** Pages translated with [signature] (the current settings) out of the chapter's pages. */
    fun progress(chapterId: Long, signature: String?, pages: Int? = null): Progress {
        val chapter = get(chapterId) ?: return Progress(0, pages ?: 0)
        val total = pages ?: chapter.pages
        val done = if (signature == null) 0 else chapter.done.count { (index, s) -> s == signature && index < total }
        return Progress(done, total)
    }

    private fun change(chapterId: Long, update: (Chapter) -> Chapter) {
        synchronized(lock) {
            val all = loaded()
            val before = all[chapterId] ?: Chapter()
            val after = update(before)
            if (after == before) return
            all[chapterId] = after
            save(all)
        }
        _changes.update { it + 1 }
    }

    private fun loaded(): MutableMap<Long, Chapter> = chapters ?: read().also { chapters = it }

    private fun read(): MutableMap<Long, Chapter> {
        if (!file.isFile) return mutableMapOf()
        return runCatching { json.decodeFromString(serializer, file.readText()).toMutableMap() }
            .getOrElse { mutableMapOf() }
    }

    private fun save(all: Map<Long, Chapter>) {
        file.parentFile?.mkdirs()
        // A crash never leaves half a file behind
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(serializer, all))
        file.delete()
        tmp.renameTo(file)
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val serializer = MapSerializer(Long.serializer(), Chapter.serializer())
    }
}
