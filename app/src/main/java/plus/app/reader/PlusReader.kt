package plus.app.reader

import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.app.di.appGraph
import okio.Buffer
import plus.app.Plus
import plus.app.translate.ChapterTranslation
import plus.app.translate.TranslateFeature
import plus.core.pipeline.PageContext
import plus.core.pipeline.PageProcessingEngine.PageState
import plus.translate.ChapterVersions
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import java.io.InputStream
import java.util.Collections
import java.util.WeakHashMap

/**
 * The two hooks in Mihon's page holders (pager and webtoon). See plus/HOOKS.md.
 *
 * [stream] hands the reader the processed version of a page when Re:Lad has one, and the original
 * otherwise (while the processed one is being made). [statusFlow] re-sends the page's status when
 * a processed version becomes ready, which makes the holder load the page again through [stream].
 *
 * A chapter is shown in the original until the reader asks for its translation ([translateChapter]:
 * the reader's ⋯ menu or the bottom bar button). It is then translated whole in the background by
 * [ChapterTranslation], which goes on when the reader closes, and the reader can switch between
 * the two versions ([showOriginal]); see [ChapterVersions].
 */
object PlusReader {

    /** Hash of each page's original image, to match pipeline events to holders. */
    private val originalHashes: MutableMap<ReaderPage, String> = Collections.synchronizedMap(WeakHashMap())

    private val sourceLanguages = java.util.concurrent.ConcurrentHashMap<Long, String>()

    /** A page whose original Re:Lad has seen: enough to ask the engine where it stands. */
    class KnownPage(val hash: String, val context: PageContext)

    private val known: MutableMap<ReaderChapter, MutableMap<Int, KnownPage>> =
        Collections.synchronizedMap(WeakHashMap())

    /** The pages of [chapter] Re:Lad has seen so far, by index. */
    fun knownPages(chapter: ReaderChapter): Map<Int, KnownPage> =
        synchronized(known) { known[chapter]?.toMap() }.orEmpty()

    private fun remember(page: ReaderPage, hash: String, context: PageContext) {
        synchronized(known) { known.getOrPut(page.chapter) { mutableMapOf() } }[page.index] = KnownPage(hash, context)
    }

    /** The page each chapter was opened at: the first one the reader showed. */
    private val openedAt: MutableMap<ReaderChapter, Int> = Collections.synchronizedMap(WeakHashMap())

    @Volatile
    private var lastToastAt = 0L

    private fun versions(context: Context): ChapterVersions = TranslateFeature.versions(Plus.graph(context))

    /**
     * "Translate the chapter": the open chapter is translated whole in the background, from the
     * page the reader is on, and shown translated as its pages come in.
     */
    fun translateChapter(activity: ReaderActivity) {
        val manga = activity.viewModel.manga ?: return
        val state = activity.viewModel.state.value
        val chapter = state.currentChapter ?: return
        ChapterTranslation.start(
            activity,
            manga,
            listOf(chapter.chapter),
            from = (state.currentPage - 1).coerceAtLeast(0),
            next = nextChapter(activity, chapter),
        )
    }

    /** Shows the open chapter's original ([original]) or its translation; the translation goes on. */
    fun showOriginal(activity: ReaderActivity, original: Boolean) {
        val chapter = activity.viewModel.state.value.currentChapter ?: return
        versions(activity).showOriginal(chapter.chapter.id, original)
    }

    /** Pages to show again at once (translated again: the original until the new version is ready). */
    private val reloads = MutableSharedFlow<ReaderPage>(extraBufferCapacity = RELOADS_BUFFER)

    /** Translate this page again: its kept translation goes and the model is asked anew. */
    fun retranslatePage(activity: ReaderActivity, page: ReaderPage) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val bytes = page.stream?.invoke()?.use { it.readBytes() } ?: return@launch
            val context = pageContext(activity, page) ?: return@launch
            versions(activity).startTranslating(page.chapter.chapter.id)
            Plus.graph(activity).engine.redo(bytes, context, TranslateFeature.id)
            reloads.emit(page)
        }
    }

    fun retranslateChapter(activity: ReaderActivity, page: ReaderPage) {
        val manga = activity.viewModel.manga ?: return
        ChapterTranslation.start(activity, manga, listOf(page.chapter.chapter), fresh = true, from = page.index)
    }

    fun stream(activity: ReaderActivity, page: ReaderPage): (() -> InputStream)? {
        val original = page.stream ?: return null
        val graph = Plus.graph(activity)
        if (!graph.anyEnabled()) return original
        val chapter = page.chapter
        synchronized(openedAt) { openedAt.getOrPut(chapter) { page.index } }
        opened(activity, chapter)
        PlusReaderStatus.show(activity, chapter)
        return {
            val bytes = original().use { it.readBytes() }
            val context = pageContext(activity, page)
            if (context == null || ImageUtil.isAnimatedAndSupported(Buffer().write(bytes))) {
                bytes.inputStream()
            } else {
                // A chapter not asked for has no translation step: the engine leaves it as it is
                val resolved = graph.engine.resolve(bytes, context)
                originalHashes[page] = resolved.originalHash
                remember(page, resolved.originalHash, context)
                val versions = versions(activity)
                val id = chapter.chapter.id
                // Drawn before (in the cache): the translator doesn't run, the page is counted here
                if (versions.translates(id) && graph.engine.state(resolved.originalHash, context) == PageState.Done) {
                    TranslateFeature.pageDone(graph, id, page.index)
                }
                if (versions.translates(id) && !versions.showsTranslation(id)) {
                    bytes.inputStream()
                } else {
                    resolved.image.inputStream()
                }
            }
        }
    }

    /** Chapters checked once since they were opened. */
    private val checked: MutableSet<ReaderChapter> = Collections.newSetFromMap(
        Collections.synchronizedMap(WeakHashMap()),
    )

    /**
     * A translated chapter opened again: its translation goes on in the background if it stopped
     * (the app was closed, a stop), and once it is done the next chapter is translated ahead.
     */
    private fun opened(activity: ReaderActivity, chapter: ReaderChapter) {
        if (!checked.add(chapter)) return
        val graph = Plus.graph(activity)
        if (!graph.isEnabled(TranslateFeature)) return
        val versions = versions(activity)
        val id = chapter.chapter.id
        if (!versions.translates(id)) return
        val manga = activity.viewModel.manga ?: return
        val next = nextChapter(activity, chapter)
        val pages = chapter.pages?.size
        val progress = versions.progress(id, TranslateFeature.textSignature(graph), pages)
        // Translated, but drawn before the lettering changed: drawn again whole from the kept
        // translations (no model), not page by page with the original showing as each is reached
        val redraw = TranslateFeature.drawnSignature(graph)?.let { versions.needsDrawing(id, it, pages) } == true
        when {
            !progress.complete || redraw -> if (!ChapterTranslation.isRunning(id)) {
                ChapterTranslation.start(
                    activity,
                    manga,
                    listOf(chapter.chapter),
                    quiet = true,
                    from = openedAt[chapter] ?: 0,
                    next = next,
                )
            }
            next != null && !versions.translates(next.id) && TranslateFeature.translatesNextChapter(graph) ->
                ChapterTranslation.start(activity, manga, listOf(next), quiet = true)
        }
    }

    private fun nextChapter(activity: ReaderActivity, chapter: ReaderChapter): Chapter? {
        val chapters = activity.viewModel.state.value.viewerChapters ?: return null
        if (chapters.currChapter !== chapter) return null
        return chapters.nextChapter?.chapter
    }

    fun statusFlow(activity: ReaderActivity, page: ReaderPage): Flow<Page.State> = channelFlow {
        val engine = Plus.graph(activity).engine
        val versions = versions(activity)
        val chapterId = page.chapter.chapter.id
        launch { page.statusFlow.collect { send(it) } }
        launch {
            engine.completed
                .filter { it == originalHashes[page] }
                .collect { send(page.status) }
        }
        launch {
            // Settings changed (feature switched, language changed...): show the page as it should be now
            engine.settingsVersion.drop(1).collect { send(page.status) }
        }
        launch {
            // Translation asked for, or original and translation switched: not on every page done
            versions.changes
                .map { versions.showsTranslation(chapterId) }
                .distinctUntilChanged()
                .drop(1)
                .collect { send(page.status) }
        }
        launch {
            // Translated again: show the page as it is now (the original until the new one is ready)
            reloads.filter { it === page }.collect { send(page.status) }
        }
        launch {
            engine.failures
                .filter { it.originalHash == originalHashes[page] }
                .collect { failure ->
                    logcat(LogPriority.WARN, failure.error) { "Re:Lad ${failure.processorId} failed" }
                    showError(activity, failure.error)
                }
        }
        awaitClose()
    }

    private fun pageContext(activity: ReaderActivity, page: ReaderPage): PageContext? {
        val manga = activity.viewModel.manga ?: return null
        val chapter = page.chapter.chapter
        return PageContext(
            mangaId = manga.id,
            mangaTitle = manga.title,
            chapterId = chapter.id,
            chapterName = chapter.name,
            pageIndex = page.index,
            sourceLanguage = sourceLanguages.getOrPut(manga.source) {
                // Runs on the reader's IO thread; the lookup is a map read once sources are loaded
                runBlocking { activity.appGraph.sourceManager.get(manga.source)?.lang }.orEmpty()
            }.ifEmpty { null },
            mangaDescription = manga.description,
            mangaGenres = manga.genre.orEmpty(),
            // Pages come in as their images finish loading, not in reading order: the page opened
            // says where reading starts (in Kirara page 8 came in first and pages 2–5 were drawn last)
            readingFrom = openedAt[page.chapter],
        )
    }

    /** One toast at a time, so a whole chapter failing for the same reason doesn't flood the screen. */
    private suspend fun showError(activity: ReaderActivity, error: Throwable) {
        val now = System.currentTimeMillis()
        if (now - lastToastAt < TOAST_INTERVAL_MS) return
        lastToastAt = now
        val message = PlusReaderStatus.reason(activity, error)
        withContext(Dispatchers.Main) {
            Toast.makeText(
                activity,
                activity.getString(eu.kanade.tachiyomi.R.string.plus_error, message),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private const val TOAST_INTERVAL_MS = 10_000L
    private const val RELOADS_BUFFER = 8
}

/** The reader behind a composable's context, if it is in the reader. */
internal fun Context.readerActivity(): ReaderActivity? {
    var context: Context? = this
    while (context != null) {
        if (context is ReaderActivity) return context
        context = (context as? ContextWrapper)?.baseContext
    }
    return null
}
