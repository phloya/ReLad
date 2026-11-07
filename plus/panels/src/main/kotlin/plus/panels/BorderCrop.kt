package plus.panels

import kotlin.math.roundToInt

/**
 * Estimates what Mihon's "crop borders" option cuts off a page, so panels found on the whole page
 * can be mapped onto the cropped one the reader shows. Mihon's image decoder crops plain white or
 * black margins: starting from each edge, the first line where more than a quarter of a percent
 * of the pixels differ from the edge's color is where the page begins. Our own code, written to
 * behave the same way (the decoder is MPL-2.0 C++, nothing is copied).
 */
internal object BorderCrop {

    /** Pixels darker than this are not white. */
    private const val NOT_WHITE_BELOW = 191

    /** Pixels lighter than this are not black. */
    private const val NOT_BLACK_ABOVE = 64

    private const val FILLED_RATIO = 0.0025

    fun estimate(image: GrayImage): Box {
        val w = image.width
        val h = image.height
        val top = scan(lines = h, length = w, reverse = false) { line, i -> image[i, line] }
        val bottom = scan(lines = h, length = w, reverse = true) { line, i -> image[i, line] }
        if (bottom - top < 2) return Box.FULL
        val left = scan(lines = w, length = bottom - top, reverse = false) { line, i -> image[line, top + i] }
        val right = scan(lines = w, length = bottom - top, reverse = true) { line, i -> image[line, top + i] }
        if (right - left < 2) return Box.FULL
        return Box(left.toFloat() / w, top.toFloat() / h, right.toFloat() / w, bottom.toFloat() / h)
    }

    /**
     * Returns where content starts when scanning [lines] lines of [length] pixels from one edge
     * (from the opposite edge if [reverse], then the returned value is the exclusive end).
     */
    private inline fun scan(lines: Int, length: Int, reverse: Boolean, pixel: (line: Int, i: Int) -> Int): Int {
        val noCrop = if (reverse) lines else 0
        val limit = (length * FILLED_RATIO / 2).roundToInt()
        val edge = if (reverse) lines - 1 else 0

        var dark = 0
        var light = 0
        for (i in 0 until length step 2) {
            if (pixel(edge, i) < NOT_WHITE_BELOW) dark++ else light++
        }
        if (dark > limit && light > limit) return noCrop
        val blackEdge = dark > limit

        val range = if (reverse) (lines - 2 downTo 1) else (1 until lines)
        for (line in range) {
            var filled = 0
            for (i in 0 until length step 2) {
                val p = pixel(line, i)
                if (if (blackEdge) p > NOT_BLACK_ABOVE else p < NOT_WHITE_BELOW) filled++
            }
            if (filled > limit) return if (reverse) line + 1 else line
        }
        return noCrop
    }
}
