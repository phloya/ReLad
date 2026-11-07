package plus.panels

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** A rectangle in image pixels. */
data class PixelRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
}

/**
 * One view of a page in panel-by-panel reading: the reader zooms to [scale] (screen pixels per
 * image pixel) centered on ([centerX], [centerY]) in image pixels. [focus] is what the reader
 * should look at (the rest may be dimmed); `null` for the whole page.
 */
data class Stop(val centerX: Float, val centerY: Float, val scale: Float, val focus: PixelRect?)

/**
 * Turns the panels of a page into the views the reader steps through. A panel that is much
 * taller than the screen (a webtoon page, a tall panel on a phone held sideways) is not shrunk
 * until it fits: it is shown at the screen's width in several overlapping steps, top to bottom.
 */
object PanelPlanner {

    /** Never zoom in further than this many times the whole-page view: small panels stay sharp. */
    const val MAX_ZOOM = 4f

    /** Steps through a tall panel overlap by this share of the screen. */
    const val OVERLAP = 0.12f

    class Viewport(val width: Float, val height: Float, val minScale: Float, val maxScale: Float)

    /**
     * @param panels panels in reading order, relative to the image.
     * @param wholePageFirst start with a view of the whole page.
     */
    fun plan(
        panels: List<Box>,
        imageWidth: Float,
        imageHeight: Float,
        view: Viewport,
        wholePageFirst: Boolean,
    ): List<Stop> {
        val page = PixelRect(0f, 0f, imageWidth, imageHeight)
        val wholePage = Stop(imageWidth / 2, imageHeight / 2, view.minScale, null)
        // Relative to the page at screen width, so a strip may still be zoomed to its width
        val cap = min(view.maxScale, maxOf(view.minScale, fitScale(page, view), view.width / imageWidth) * MAX_ZOOM)
        val pad = 0.012f * max(imageWidth, imageHeight)

        val stops = mutableListOf<Stop>()
        if (wholePageFirst) stops += wholePage
        val rects = panels.ifEmpty { listOf(Box.FULL) }.map { box ->
            PixelRect(
                max(0f, box.left * imageWidth - pad),
                max(0f, box.top * imageHeight - pad),
                min(imageWidth, box.right * imageWidth + pad),
                min(imageHeight, box.bottom * imageHeight + pad),
            )
        }
        for (rect in rects) {
            val fit = fitScale(rect, view)
            val widthFit = min(view.width / rect.width, cap)
            val isWholePage = rect.width * rect.height >= 0.85f * imageWidth * imageHeight
            val tall = rect.height * widthFit > view.height * 1.1f && rect.width * fit < 0.7f * view.width
            when {
                tall && widthFit > view.minScale -> stops += steps(rect, widthFit, view)
                isWholePage -> stops += wholePage
                else -> {
                    val scale = fit.coerceIn(view.minScale, max(view.minScale, cap))
                    stops += Stop(rect.centerX, rect.centerY, scale, rect)
                }
            }
        }
        return stops.fold(mutableListOf()) { list, stop ->
            if (list.isEmpty() || !list.last().sameAs(stop)) list += stop
            list
        }
    }

    private fun fitScale(rect: PixelRect, view: Viewport) = min(view.width / rect.width, view.height / rect.height)

    /** Screens of [rect] at [scale], top to bottom, spread evenly with some overlap. */
    private fun steps(rect: PixelRect, scale: Float, view: Viewport): List<Stop> {
        val screen = view.height / scale
        val step = screen * (1 - OVERLAP)
        val count = ceil((rect.height - screen) / step).toInt() + 1
        val last = rect.bottom - screen
        return (0 until count).map { i ->
            val top = if (count == 1) rect.top else rect.top + (last - rect.top) * i / (count - 1)
            Stop(
                centerX = rect.centerX,
                centerY = top + screen / 2,
                scale = scale,
                focus = PixelRect(rect.left, top, rect.right, min(rect.bottom, top + screen)),
            )
        }
    }

    private fun Stop.sameAs(other: Stop): Boolean =
        kotlin.math.abs(scale - other.scale) < 0.01f * scale &&
            kotlin.math.abs(centerX - other.centerX) * scale < 4f &&
            kotlin.math.abs(centerY - other.centerY) * scale < 4f
}
