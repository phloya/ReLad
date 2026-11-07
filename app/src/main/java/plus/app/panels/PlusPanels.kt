package plus.app.panels

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.Toast
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.pager.Pager
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerPageHolder
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import logcat.LogPriority
import okio.BufferedSource
import plus.app.Plus
import plus.panels.Box
import plus.panels.PageLayout
import plus.panels.PanelDetector
import plus.panels.PanelImages
import plus.panels.PanelStore
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.util.Collections
import java.util.WeakHashMap

/**
 * The hooks of panel-by-panel reading in Mihon's reader (see plus/HOOKS.md). Each viewer gets its
 * own controller the first time it asks: [PagerPanels] for the page modes, [WebtoonPanels] for
 * the strip modes. With the mode off, every hook returns at once and nothing is detected.
 */
object PlusPanels {

    private val pagers: MutableMap<PagerViewer, PagerPanels> = Collections.synchronizedMap(WeakHashMap())
    private val webtoons: MutableMap<WebtoonViewer, WebtoonPanels> = Collections.synchronizedMap(WeakHashMap())

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var store: PanelStore? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    private val detection = Dispatchers.Default.limitedParallelism(2)

    @Volatile
    private var preferences: PanelsPreferences? = null

    internal fun preferences(context: Context): PanelsPreferences =
        preferences ?: PanelsPreferences(Plus.graph(context)).also { preferences = it }

    fun isEnabled(context: Context): Boolean = preferences(context).enabled.get()

    // ---- Page modes (left to right, right to left, vertical pages) ----

    /** A page holder got its image (after Mihon split or rotated it). Called on a background thread. */
    fun onPagerImage(viewer: PagerViewer, holder: PagerPageHolder, image: BufferedSource) {
        if (!isEnabled(viewer.activity)) return
        pager(viewer).onImage(holder, image)
    }

    /** Mihon's landscape zoom animates wide pages by itself; in panel mode the panels decide. */
    fun landscapeZoom(viewer: PagerViewer, enabled: Boolean): Boolean = enabled && !isEnabled(viewer.activity)

    fun onPageSelected(viewer: PagerViewer, page: ReaderPage, forward: Boolean) {
        pager(viewer).onPageSelected(page, forward)
    }

    /** Next or previous panel instead of the next page. `true` when handled (stay on the page). */
    fun onPagerMove(viewer: PagerViewer, toRight: Boolean): Boolean {
        if (!isEnabled(viewer.activity)) return false
        return pager(viewer).move(toRight)
    }

    /** Swipes in panel mode go to the next or previous panel. [dispatch] is the pager's own handling. */
    fun dispatchTouch(pager: Pager, event: MotionEvent, dispatch: (MotionEvent) -> Boolean): Boolean {
        val controller = synchronized(pagers) { pagers.values.firstOrNull { it.pager === pager } }
            ?: return dispatch(event)
        return controller.dispatchTouch(event, dispatch)
    }

    private fun pager(viewer: PagerViewer) = synchronized(pagers) { pagers.getOrPut(viewer) { PagerPanels(viewer) } }

    // ---- Strip modes (webtoon, continuous vertical) ----

    fun onWebtoonImage(viewer: WebtoonViewer, frame: ReaderPageImageView, image: BufferedSource) {
        if (!isEnabled(viewer.activity)) return
        webtoon(viewer).onImage(frame, image)
    }

    /** Scroll to the next or previous piece of the strip instead of a fixed distance. */
    fun onWebtoonScroll(viewer: WebtoonViewer, down: Boolean): Boolean {
        if (!isEnabled(viewer.activity)) return false
        return webtoon(viewer).scroll(down)
    }

    private fun webtoon(viewer: WebtoonViewer) =
        synchronized(webtoons) { webtoons.getOrPut(viewer) { WebtoonPanels(viewer) } }

    // ---- Switching ----

    /** The reader button. */
    fun toggle(context: Context) {
        val prefs = preferences(context)
        val enabled = !prefs.enabled.get()
        prefs.enabled.set(enabled)
        Toast.makeText(
            context,
            if (enabled) R.string.plus_panels_turned_on else R.string.plus_panels_turned_off,
            Toast.LENGTH_SHORT,
        ).show()
        onSwitched()
    }

    /** Reloads the open readers' pages, so they start or stop finding panels. */
    fun onSwitched() {
        main.post {
            synchronized(pagers) { pagers.values.toList() }.forEach { it.onSwitched() }
            synchronized(webtoons) { webtoons.values.toList() }.forEach { it.onSwitched() }
        }
    }

    // ---- Detection ----

    /**
     * Finds the panels of [image] in the background and calls [onDone] on the main thread.
     * The bytes are copied right away: the reader goes on to decode the same source.
     */
    internal fun detect(context: Context, image: BufferedSource, onDone: (PageLayout) -> Unit) {
        val bytes = image.peek().readByteArray()
        val graph = Plus.graph(context)
        graph.scope.launch(detection) {
            val layout = try {
                store(context).layout(bytes) { data ->
                    val start = System.nanoTime()
                    val gray = PanelImages.decode(data)
                    val decoded = System.nanoTime()
                    val found = gray?.let(PanelDetector::analyze) ?: wholePage
                    logcat {
                        "Re:Lad panels: ${found.panels.size} panels, ${found.gaps.size} gaps on " +
                            "${gray?.width}x${gray?.height}: decoded in ${(decoded - start) / 1_000_000} ms, " +
                            "analyzed in ${(System.nanoTime() - decoded) / 1_000_000} ms"
                    }
                    found
                }
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "Re:Lad panels: detection failed" }
                wholePage
            }
            main.post { onDone(layout) }
        }
    }

    /** What a page that cannot be read is treated as: one panel, the whole page. */
    private val wholePage = PageLayout(listOf(Box.FULL), Box.FULL, emptyList(), 1f)

    private fun store(context: Context): PanelStore = store ?: synchronized(this) {
        store ?: PanelStore(File(context.applicationContext.cacheDir, "relad_panels")).also { store = it }
    }
}
