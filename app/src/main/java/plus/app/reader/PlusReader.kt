package plus.app.reader

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.app.di.appGraph
import okio.Buffer
import plus.app.Plus
import plus.app.PlusGraph
import plus.app.translate.ChapterTranslation
import plus.app.translate.TranslateFeature
import plus.core.pipeline.PageContext
import plus.core.pipeline.PageProcessingEngine
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import java.io.InputStream
import java.util.Collections
import java.util.WeakHashMap

/**
 * The two hooks in Mihon's page holders (pager and webtoon). See plus/HOOKS.md.
 *
 * [stream] hands the reader the processed version of a page when Re:Lad has one, and the original
 * otherwise (while the processed one is being made). [statusFlow] re-sends the page's status when
 * a processed version becomes ready, which makes the holder load the page again through [stream].
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

    /** Chapters whose pages are being made ready ahead, one walk each. */
    private val preparing: MutableMap<ReaderChapter, Job> = Collections.synchronizedMap(WeakHashMap())

    /** The page each chapter was opened at: the first one the reader showed. */
    private val openedAt: MutableMap<ReaderChapter, Int> = WeakHashMap()

    @Volatile
    private var lastToastAt = 0L

    private val _showOriginal = MutableStateFlow(false)

    /**
     * The reader shows pages as they came, not translated or enhanced (the button in the reader's
     * bottom bar). Processing goes on meanwhile, so switching back shows what got done.
     */
    val showOriginal: StateFlow<Boolean> = _showOriginal.asStateFlow()

    fun toggleOriginal() = _showOriginal.update { !it }

    /**
     * Whether a feature works on the whole open chapter (translation: "the whole chapter at once"):
     * Mihon then loads all its online pages, not only the next few on mobile data.
     */
    fun loadsWholeChapter(context: Context): Boolean {
        val graph = Plus.graph(context)
        return graph.features.any { it.preparesWholeChapter(graph) }
    }

    /** Pages to show again at once (translated again: the original until the new version is ready). */
    private val reloads = MutableSharedFlow<ReaderPage>(extraBufferCapacity = RELOADS_BUFFER)

    /** Translate this page again: its kept translation goes and the model is asked anew. */
    fun retranslatePage(activity: ReaderActivity, page: ReaderPage) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val bytes = page.stream?.invoke()?.use { it.readBytes() } ?: return@launch
            val context = pageContext(activity, page) ?: return@launch
            Plus.graph(activity).engine.redo(bytes, context, TranslateFeature.id)
            reloads.emit(page)
        }
    }

    /** Translate the page's whole chapter again, in the background as Translate in the chapter list does. */
    fun retranslateChapter(activity: ReaderActivity, page: ReaderPage) {
        val manga = activity.viewModel.manga ?: return
        ChapterTranslation.start(activity, manga, listOf(page.chapter.chapter), fresh = true)
    }

    fun stream(activity: ReaderActivity, page: ReaderPage): (() -> InputStream)? {
        val original = page.stream ?: return null
        val graph = Plus.graph(activity)
        if (!graph.anyEnabled()) return original
        prepareChapter(activity, page)
        PlusReaderStatus.show(activity, page.chapter)
        return {
            val bytes = original().use { it.readBytes() }
            val context = pageContext(activity, page)
            if (context == null || ImageUtil.isAnimatedAndSupported(Buffer().write(bytes))) {
                bytes.inputStream()
            } else {
                val resolved = graph.engine.resolve(bytes, context)
                originalHashes[page] = resolved.originalHash
                remember(page, resolved.originalHash, context)
                if (showOriginal.value) bytes.inputStream() else resolved.image.inputStream()
            }
        }
    }

    fun statusFlow(activity: ReaderActivity, page: ReaderPage): Flow<Page.State> = channelFlow {
        val engine = Plus.graph(activity).engine
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
            // Original or translation switched in the reader
            showOriginal.drop(1).collect { send(page.status) }
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

    /**
     * When a feature asks for it (translation: "the whole chapter at once"), every page of the
     * chapter is processed as soon as it opens: from the page opened to the end, then the pages
     * before it, [AHEAD] at a time. Online pages are taken as Mihon downloads them (when it loads
     * ahead over Wi-Fi only, on mobile data that is the next few). The walk ends with the reader
     * and starts over when settings change (pages already done come from the cache).
     */
    private fun prepareChapter(activity: ReaderActivity, from: ReaderPage) {
        val chapter = from.chapter
        synchronized(preparing) {
            openedAt.getOrPut(chapter) { from.index }
            if (preparing[chapter]?.isActive == true) return
            preparing[chapter] = activity.lifecycleScope.launch(Dispatchers.IO) {
                val graph = Plus.graph(activity)
                graph.engine.settingsVersion.collectLatest {
                    if (graph.features.none { it.preparesWholeChapter(graph) }) return@collectLatest
                    val pages = chapter.pages ?: return@collectLatest
                    val start = from.index.coerceIn(0, pages.size)
                    val order = pages.drop(start) + pages.take(start).reversed()
                    // Taken in page order, so the title's notes grow page by page as when reading
                    val gate = Semaphore(AHEAD)
                    coroutineScope {
                        for (page in order) {
                            gate.acquire()
                            launch {
                                try {
                                    prepare(activity, graph, page)
                                } finally {
                                    gate.release()
                                }
                            }
                        }
                    }
                    translateNextChapter(activity, graph, chapter)
                }
            }
        }
    }

    /**
     * The open chapter is done: the next one is translated in the background (when the reader
     * wants it), so turning to it there is nothing to wait for. Not after a failure: the next
     * chapter would only stop the same way.
     */
    private fun translateNextChapter(activity: ReaderActivity, graph: PlusGraph, chapter: ReaderChapter) {
        if (!TranslateFeature.translatesNextChapter(graph)) return
        val manga = activity.viewModel.manga ?: return
        val chapters = activity.viewModel.state.value.viewerChapters ?: return
        if (chapters.currChapter !== chapter) return
        val next = chapters.nextChapter?.chapter ?: return
        val failed = knownPages(chapter).values.any { known ->
            graph.engine.state(known.hash, known.context) is PageProcessingEngine.PageState.Failed
        }
        if (!failed) ChapterTranslation.start(activity, manga, listOf(next), quiet = true)
    }

    private suspend fun prepare(activity: ReaderActivity, graph: PlusGraph, page: ReaderPage) {
        // Online pages: wait for Mihon to download the image
        val state = page.statusFlow.first { it == Page.State.Ready || it is Page.State.Error }
        if (state != Page.State.Ready) return
        val bytes = try {
            page.stream?.invoke()?.use { it.readBytes() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Re:Lad couldn't read page ${page.index} ahead" }
            null
        } ?: return
        if (ImageUtil.isAnimatedAndSupported(Buffer().write(bytes))) return
        val context = pageContext(activity, page) ?: return
        remember(page, PageProcessingEngine.sha256(bytes), context)
        graph.engine.prepare(bytes, context)
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
            readingFrom = synchronized(preparing) { openedAt[page.chapter] },
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

    /** Pages of a chapter made ready at once: as many as the translation step takes at a time. */
    private const val AHEAD = 10
}
