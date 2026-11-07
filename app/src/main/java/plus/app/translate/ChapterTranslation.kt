package plus.app.translate

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.app.di.appGraph
import okio.Buffer
import plus.app.Plus
import plus.app.PlusGraph
import plus.app.reader.PlusReaderStatus
import plus.core.ai.AiException
import plus.core.pipeline.PageContext
import plus.core.pipeline.PageProcessingEngine
import plus.core.pipeline.PageProcessingEngine.PageState
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import mihon.icons.materialsymbols.R as MaterialSymbolsR

/**
 * "Translate" on selected chapters in the manga screen: the chapters are translated in the
 * background, in reading order, with a notification that shows how far it got and, if it stopped,
 * why. Pages are loaded the way the reader loads them (online, downloaded or local), so when the
 * chapter is opened the translated pages come from the cache at once.
 */
object ChapterTranslation {

    /** Chapters being translated now: pressing Translate again doesn't start them twice. */
    private val running = ConcurrentHashMap.newKeySet<Long>()

    fun isAvailable(context: Context): Boolean {
        val graph = Plus.graph(context)
        return graph.isEnabled(TranslateFeature)
    }

    /** [fresh]: translate again (Translate again in the reader), not only the pages not done yet. */
    fun start(context: Context, manga: Manga, chapters: List<Chapter>, fresh: Boolean = false) {
        val app = context.applicationContext
        val graph = Plus.graph(app)
        val todo = chapters
            .sortedWith(compareBy<Chapter> { it.chapterNumber }.thenByDescending { it.sourceOrder })
            .filter { running.add(it.id) }
        if (todo.isEmpty()) return
        // Pressed again after a stop (no credits, the model refused...): pages that failed a moment
        // ago are tried again now, pages already done come from the kept translations
        graph.engine.retryFailed()
        Toast.makeText(app, app.getString(R.string.plus_translate_started, todo.size), Toast.LENGTH_SHORT).show()
        graph.scope.launch(Dispatchers.IO) {
            // One notice per title: pressing Translate again after a stop replaces the stop notice
            val notifier = Notifier(app, manga.title, NOTIFICATION_BASE + (manga.id and TITLE_BITS).toInt())
            var translated = 0
            var error: Throwable? = null
            try {
                for ((i, chapter) in todo.withIndex()) {
                    val failure = translate(app, graph, manga, chapter, fresh) { done, total ->
                        notifier.progress(chapter.name, i + 1, todo.size, done, total)
                    }
                    if (failure == null) translated++ else error = failure
                    // No provider, no key, a key not accepted, no credits: the next chapter would fail too
                    if (failure != null && stopsAll(failure)) break
                }
            } finally {
                todo.forEach { running.remove(it.id) }
            }
            notifier.finish(translated, todo.size, error)
            withContext(Dispatchers.Main) {
                val text = if (error != null) {
                    app.getString(R.string.plus_status_stopped, PlusReaderStatus.reason(app, error!!))
                } else {
                    app.getString(R.string.plus_translate_finished, translated, todo.size)
                }
                Toast.makeText(app, text, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Translates [chapter]: `null` if every page is done, otherwise why a page wasn't. */
    private suspend fun translate(
        context: Context,
        graph: PlusGraph,
        manga: Manga,
        chapter: Chapter,
        fresh: Boolean,
        progress: (done: Int, total: Int) -> Unit,
    ): Throwable? {
        val app = context.appGraph
        val source = app.sourceManager.getOrStub(manga.source)
        val readerChapter = ReaderChapter(chapter)
        readerChapter.ref()
        try {
            ChapterLoader(
                context,
                app.downloadManager,
                app.downloadProvider,
                app.chapterCache,
                graph.scope,
                // Here the whole chapter is wanted: load every page, Wi-Fi or not
                canLoadAhead = { true },
                manga,
                source,
            ).loadChapter(readerChapter)
            val pages = readerChapter.pages ?: return null
            val done = AtomicInteger(0)
            var error: Throwable? = null
            progress(0, pages.size)
            // In page order, as many at a time as the translation step takes: the title's notes grow page by page
            val gate = Semaphore(AT_ONCE)
            coroutineScope {
                for (page in pages) {
                    gate.acquire()
                    launch {
                        try {
                            when (val state = translatePage(graph, manga, chapter, source.lang, page, fresh)) {
                                PageState.Done -> progress(done.incrementAndGet(), pages.size)
                                is PageState.Failed -> error = state.error
                                PageState.Waiting -> Unit
                            }
                        } finally {
                            gate.release()
                        }
                    }
                }
            }
            return error
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Re:Lad couldn't load ${chapter.name} to translate it" }
            return e
        } finally {
            readerChapter.unref()
        }
    }

    private suspend fun translatePage(
        graph: PlusGraph,
        manga: Manga,
        chapter: Chapter,
        sourceLanguage: String,
        page: ReaderPage,
        fresh: Boolean,
    ): PageState {
        // Online pages: the loader downloads them in order; a page that failed to download is skipped
        val status = page.statusFlow.first { it == Page.State.Ready || it is Page.State.Error }
        if (status != Page.State.Ready) {
            return PageState.Failed((status as? Page.State.Error)?.error ?: IllegalStateException("page"))
        }
        val bytes = page.stream?.invoke()?.use { it.readBytes() } ?: return PageState.Waiting
        if (ImageUtil.isAnimatedAndSupported(Buffer().write(bytes))) return PageState.Done
        val context = PageContext(
            mangaId = manga.id,
            mangaTitle = manga.title,
            chapterId = chapter.id,
            chapterName = chapter.name,
            pageIndex = page.index,
            sourceLanguage = sourceLanguage.ifEmpty { null },
            mangaDescription = manga.description,
            mangaGenres = manga.genre.orEmpty(),
        )
        val hash = PageProcessingEngine.sha256(bytes)
        if (fresh) graph.engine.redo(bytes, context, TranslateFeature.id)
        graph.engine.prepare(bytes, context)
        // Being translated already (the reader has it open): wait for it to finish
        var state = graph.engine.state(hash, context)
        var waited = 0L
        while (state == PageState.Waiting && waited < MAX_WAIT_MS) {
            delay(POLL_MS)
            waited += POLL_MS
            state = graph.engine.state(hash, context)
        }
        return state
    }

    private fun stopsAll(error: Throwable): Boolean {
        val ai = error as? AiException ?: return false
        return ai.kind == AiException.Kind.NoProvider ||
            ai.kind == AiException.Kind.NoKey ||
            (ai.kind == AiException.Kind.Http && ai.httpCode in listOf(401, 402, 403, 404))
    }

    private class Notifier(private val context: Context, private val title: String, private val id: Int) {
        private val manager = NotificationManagerCompat.from(context)

        init {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL,
                    context.getString(R.string.plus_translate_channel),
                    NotificationManager.IMPORTANCE_LOW,
                )
                context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
            }
        }

        fun progress(chapterName: String, chapter: Int, chapters: Int, done: Int, total: Int) {
            val text = context.getString(R.string.plus_translate_progress, chapterName, done, total) +
                if (chapters >
                    1
                ) {
                    " · ${context.getString(R.string.plus_translate_progress_chapters, chapter, chapters)}"
                } else {
                    ""
                }
            notify(
                builder()
                    .setContentText(text)
                    .setProgress(total, done, false)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true),
            )
        }

        fun finish(translated: Int, chapters: Int, error: Throwable?) {
            val text = if (error != null) {
                context.getString(R.string.plus_status_stopped, PlusReaderStatus.reason(context, error)) +
                    "\n" + context.getString(R.string.plus_translate_continue_hint)
            } else {
                context.getString(R.string.plus_translate_finished, translated, chapters)
            }
            notify(
                builder()
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setAutoCancel(true),
            )
        }

        private fun builder() = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(MaterialSymbolsR.drawable.rounded_eyeglasses_2)
            .setContentTitle(context.getString(R.string.plus_translate_notification_title, title))

        private fun notify(builder: NotificationCompat.Builder) {
            // Without the permission (Android 13+, refused by the user) the toasts still tell the start and the end
            if (!manager.areNotificationsEnabled()) return
            try {
                manager.notify(id, builder.build())
            } catch (e: SecurityException) {
                logcat(LogPriority.WARN, e) { "Re:Lad can't post the translation notification" }
            }
        }
    }

    private const val CHANNEL = "relad_translate"
    private const val NOTIFICATION_BASE = 0x7E1A0
    private const val TITLE_BITS = 0xFFFL
    private const val AT_ONCE = 6
    private const val POLL_MS = 1_000L
    private const val MAX_WAIT_MS = 180_000L
}
