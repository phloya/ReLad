package plus.panels

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Finds empty horizontal bands of a page: rows of one color (any color, a gradient counts too)
 * that are safe places to cut a vertical strip into screens. A row with even a short line of text
 * or the tip of a speech balloon is not empty.
 */
internal object StripGaps {

    /** Most pixels of an empty row are within this of each other (JPEG noise is a few levels). */
    private const val FLAT_RANGE = 20

    private const val MAX_SAMPLES = 400

    fun find(image: GrayImage): List<Span> {
        val w = image.width
        val h = image.height
        val step = max(1, w / MAX_SAMPLES)
        val samples = IntArray((w + step - 1) / step)
        // Ignore a few odd pixels (noise, specks), but no more than half a percent of the row
        val skip = samples.size / 200
        val flat = BooleanArray(h) { y ->
            var n = 0
            for (x in 0 until w step step) samples[n++] = image[x, y]
            samples.sort(0, n)
            samples[n - 1 - skip] - samples[skip] <= FLAT_RANGE
        }

        val minRun = max(3, (w * 0.008f).roundToInt())
        val gaps = mutableListOf<Span>()
        var y = 0
        while (y < h) {
            if (!flat[y]) {
                y++
                continue
            }
            val start = y
            while (y < h && flat[y]) y++
            if (y - start >= minRun) gaps += Span(start.toFloat() / h, y.toFloat() / h)
        }
        return gaps
    }
}
