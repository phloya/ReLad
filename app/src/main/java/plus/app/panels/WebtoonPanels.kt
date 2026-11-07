package plus.app.panels

import androidx.core.view.children
import androidx.recyclerview.widget.RecyclerView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonStripImageView
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import okio.BufferedSource
import plus.panels.Box
import plus.panels.PageLayout
import plus.panels.StripPlanner
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Panel-by-panel reading in the strip modes (webtoon, continuous vertical). The strip is cut into
 * screens at its empty bands, so a screen never ends in the middle of a panel and long empty
 * stretches are skipped ([StripPlanner]). Taps and volume keys go a piece at a time; a swipe
 * moves one piece too, like turning a page.
 */
internal class WebtoonPanels(private val viewer: WebtoonViewer) {

    private val recycler = viewer.recycler
    private val prefs = PlusPanels.preferences(viewer.activity)
    private val layouts: MutableMap<ReaderPageImageView, Entry> = Collections.synchronizedMap(WeakHashMap())

    private var dragging = false
    private var flung = false
    private var dragDistance = 0

    private class Entry {
        @Volatile var generation = 0

        @Volatile var layout: PageLayout? = null
    }

    init {
        recycler.post { install() }
    }

    /** Called on a background thread with the image the frame is about to show. */
    fun onImage(frame: ReaderPageImageView, image: BufferedSource) {
        val entry = synchronized(layouts) { layouts.getOrPut(frame) { Entry() } }
        // A frame is reused for another page: forget the old page at once
        entry.layout = null
        val generation = ++entry.generation
        PlusPanels.detect(viewer.activity, image) { layout ->
            if (entry.generation == generation) entry.layout = layout
        }
    }

    /** Taps and keys. `false` lets Mihon scroll its usual distance. */
    fun scroll(down: Boolean): Boolean {
        if (!active()) return false
        val dy = plan(down, shift = 0) ?: return false
        if (viewer.config.usePageTransitions) recycler.smoothScrollBy(0, dy) else recycler.scrollBy(0, dy)
        return true
    }

    fun onSwitched() {
        layouts.clear()
        viewer.config.imagePropertyChangedListener?.invoke()
    }

    private fun install() {
        recycler.onFlingListener = object : RecyclerView.OnFlingListener() {
            override fun onFling(velocityX: Int, velocityY: Int): Boolean {
                if (!dragging || !active()) return false
                val dy = plan(down = velocityY > 0, shift = dragDistance) ?: return false
                flung = true
                recycler.smoothScrollBy(0, dy - dragDistance)
                return true
            }
        }
        recycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                        dragging = true
                        flung = false
                        dragDistance = 0
                    } else if (dragging) {
                        dragging = false
                        if (!flung && newState == RecyclerView.SCROLL_STATE_IDLE && active()) settle()
                    }
                }

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dragging) dragDistance += dy
                }
            },
        )
    }

    /** A slow drag let go: on to the next piece if it went far enough, otherwise back. */
    private fun settle() {
        val distance = dragDistance
        if (distance == 0) return
        val dy = if (abs(distance) >= SWIPE_SHARE * recycler.height) {
            plan(down = distance > 0, shift = distance)?.minus(distance) ?: return
        } else {
            -distance
        }
        recycler.smoothScrollBy(0, dy)
    }

    private fun active() = prefs.enabled.get() && recycler.scaleX == 1f && recycler.height > 0

    /**
     * Scroll distance for the next or previous piece, seen from where the screen was [shift]
     * pixels ago (the start of a drag), or `null` when too little is known.
     */
    private fun plan(down: Boolean, shift: Int): Int? {
        val items = recycler.children.map { item(it, shift.toFloat()) }.toList()
        val screen = recycler.height.toFloat()
        val dy = if (down) StripPlanner.next(items, screen) else StripPlanner.previous(items, screen)
        return dy?.roundToInt()
    }

    private fun item(child: android.view.View, shift: Float): StripPlanner.Item {
        val top = child.top + shift
        val bottom = child.bottom + shift
        val frame = child as? ReaderPageImageView ?: return StripPlanner.Item(top, bottom, null)
        val strip = frame.children.firstOrNull { it is WebtoonStripImageView && it.isShown && it.height > 0 }
        val layout = layouts[frame]?.layout
        if (strip == null || layout == null) return StripPlanner.Item(top, bottom, null)

        val crop = if (viewer.config.imageCropBorders) layout.crop else Box.FULL
        val imageTop = top + strip.top
        val imageHeight = strip.height.toFloat()
        val gaps = layout.gaps.mapNotNull { gap ->
            val start = ((gap.start - crop.top) / crop.height).coerceIn(0f, 1f)
            val end = ((gap.end - crop.top) / crop.height).coerceIn(0f, 1f)
            if (end <= start) null else StripPlanner.Band(imageTop + start * imageHeight, imageTop + end * imageHeight)
        }.toMutableList()
        // Whatever of the frame is not the image (padding) is empty too
        if (imageTop > top) gaps += StripPlanner.Band(top, imageTop)
        if (imageTop + imageHeight < bottom) gaps += StripPlanner.Band(imageTop + imageHeight, bottom)
        return StripPlanner.Item(top, bottom, gaps)
    }

    companion object {
        /** A drag this far (share of the screen) counts as a swipe to the next piece. */
        private const val SWIPE_SHARE = 0.12f
    }
}
