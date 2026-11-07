package plus.app.reader

import android.widget.Toast
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.app.di.appGraph
import okio.Buffer
import plus.app.Plus
import plus.core.ai.AiException
import plus.core.pipeline.PageContext
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

    @Volatile
    private var lastToastAt = 0L

    fun stream(activity: ReaderActivity, page: ReaderPage): (() -> InputStream)? {
        val original = page.stream ?: return null
        val graph = Plus.graph(activity)
        if (!graph.anyEnabled()) return original
        return {
            val bytes = original().use { it.readBytes() }
            val context = pageContext(activity, page)
            if (context == null || ImageUtil.isAnimatedAndSupported(Buffer().write(bytes))) {
                bytes.inputStream()
            } else {
                val resolved = graph.engine.resolve(bytes, context)
                originalHashes[page] = resolved.originalHash
                resolved.image.inputStream()
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
        )
    }

    /** One toast at a time, so a whole chapter failing for the same reason doesn't flood the screen. */
    private suspend fun showError(activity: ReaderActivity, error: Throwable) {
        val now = System.currentTimeMillis()
        if (now - lastToastAt < TOAST_INTERVAL_MS) return
        lastToastAt = now
        val message = (error as? AiException)?.message ?: error.message ?: error.javaClass.simpleName
        withContext(Dispatchers.Main) {
            Toast.makeText(
                activity,
                activity.getString(eu.kanade.tachiyomi.R.string.plus_error, message),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private const val TOAST_INTERVAL_MS = 10_000L
}
