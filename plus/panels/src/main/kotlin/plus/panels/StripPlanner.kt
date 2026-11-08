package plus.panels

import kotlin.math.max
import kotlin.math.min

/**
 * Decides how far a vertical strip (webtoon mode) scrolls for "next" and "previous" in
 * panel-by-panel reading, from what is laid out on screen right now.
 *
 * Content between empty bands is a "piece" (usually a panel). Next brings the first piece that
 * the bottom edge cuts to the top of the screen; an empty band under the screen is skipped
 * entirely. A piece taller than the screen is scrolled through a screen at a time, with overlap.
 * Previous does the mirror image. Plain screen-by-screen scrolling, by contrast, cuts panels in
 * half and makes the reader scroll through empty space.
 */
object StripPlanner {

    /** A vertical band, in screen pixels (0 = top of the screen). */
    data class Band(val start: Float, val end: Float) {
        val length get() = end - start
    }

    /**
     * A page (or a chapter transition) laid out on screen from [top] to [bottom]. [gaps] are its
     * empty bands; `null` when they are not known (the page is still loading): then the whole
     * item counts as content.
     */
    class Item(val top: Float, val bottom: Float, val gaps: List<Band>?)

    private const val MARGIN = 0.03f
    private const val MIN_STEP = 0.05f

    /** How far to scroll down, or `null` to scroll the usual way (not enough is laid out). */
    fun next(items: List<Item>, screen: Float): Float? {
        if (items.isEmpty() || items.last().bottom < screen) return null
        val pieces = pieces(items)
        val bottom = screen
        val cut = pieces.firstOrNull { it.start < bottom - 1 && it.end > bottom + 1 }
        val target = if (cut == null) {
            val next = pieces.firstOrNull { it.start >= bottom - 1 } ?: return null
            topAligned(next, pieces, screen)
        } else if (cut.start <= 0.15f * screen || (cut.length > screen && cut.start < 0.5f * screen)) {
            screen * (1 - PanelPlanner.OVERLAP)
        } else {
            topAligned(cut, pieces, screen)
        }
        return if (target < MIN_STEP * screen) screen * (1 - PanelPlanner.OVERLAP) else target
    }

    /** How far to scroll up (a negative number), or `null` to scroll the usual way. */
    fun previous(items: List<Item>, screen: Float): Float? {
        if (items.isEmpty() || items.first().top > 0f) return null
        val pieces = pieces(items)
        val cut = pieces.firstOrNull { it.start < -1 && it.end > 1 }
        val target = if (cut != null &&
            (cut.end >= 0.85f * screen || (cut.length > screen && cut.end > 0.5f * screen))
        ) {
            // Inside a piece taller than the screen: go up a screen, but not past its top
            max(-screen * (1 - PanelPlanner.OVERLAP), topAligned(cut, pieces, screen))
        } else {
            val previous = cut ?: pieces.lastOrNull { it.end <= 1 } ?: return null
            if (previous.length + MARGIN * screen <= screen) {
                topAligned(previous, pieces, screen)
            } else {
                // Taller than the screen: show its last screen
                val after = pieces.firstOrNull { it.start > previous.end }?.start ?: Float.POSITIVE_INFINITY
                previous.end + min(MARGIN * screen, after - previous.end) - screen
            }
        }
        return if (target > -MIN_STEP * screen) -screen * (1 - PanelPlanner.OVERLAP) else target
    }

    /** Scroll that puts [piece] at the top of the screen, with a little of the empty band above it. */
    private fun topAligned(piece: Band, pieces: List<Band>, screen: Float): Float {
        val before = pieces.lastOrNull { it.end < piece.start }?.end ?: Float.NEGATIVE_INFINITY
        return piece.start - min(MARGIN * screen, piece.start - before)
    }

    /** Content bands of all items, top to bottom; pieces that touch across pages are one piece. */
    internal fun pieces(items: List<Item>): List<Band> {
        val raw = mutableListOf<Band>()
        for (item in items.sortedBy { it.top }) {
            var at = item.top
            for (gap in item.gaps.orEmpty().sortedBy { it.start }) {
                val start = gap.start.coerceIn(item.top, item.bottom)
                val end = gap.end.coerceIn(item.top, item.bottom)
                if (start > at) raw += Band(at, start)
                at = max(at, end)
            }
            if (item.bottom > at) raw += Band(at, item.bottom)
        }
        val merged = mutableListOf<Band>()
        for (band in raw) {
            val last = merged.lastOrNull()
            if (last != null && band.start - last.end <= 1f) {
                merged[merged.lastIndex] = Band(last.start, max(last.end, band.end))
            } else {
                merged += band
            }
        }
        return merged
    }
}
