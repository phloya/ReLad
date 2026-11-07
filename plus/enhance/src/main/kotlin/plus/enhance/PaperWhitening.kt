package plus.enhance

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Makes grey or yellowed paper white on black-and-white pages, like scanlators do with "levels".
 *
 * The paper is the most common light tone of the page. Each colour channel is stretched so that
 * the paper becomes white; ink and screentone keep their place relative to it. Pages with real
 * colour, dark pages and pages that are already white are left alone.
 */
object PaperWhitening {

    /** Per-channel multipliers that make the paper white. */
    data class Gains(val red: Float, val green: Float, val blue: Float)

    /** Paper darker than this is not paper (dark page, night scene): leave the page alone. */
    private const val MIN_PAPER = 150

    /** Paper this light is already white enough. */
    private const val WHITE_ENOUGH = 250

    /** Pixels farther than this from grey (after whitening) count as coloured. */
    private const val COLOUR_DISTANCE = 40

    /** Pages with more coloured pixels than this are colour pages. */
    private const val MAX_COLOURED_SHARE = 0.03

    /** Finds the gains for [pixels] (ARGB), or `null` when the page should not be changed. */
    fun analyze(pixels: IntArray): Gains? {
        if (pixels.isEmpty()) return null
        val step = maxOf(1, pixels.size / 250_000) // a sample is enough

        val histogram = IntArray(256)
        for (i in pixels.indices step step) histogram[luma(pixels[i])]++

        val paper = paperTone(histogram) ?: return null
        if (paper >= WHITE_ENOUGH) return null

        // Paper colour per channel: the light end of the pixels around the paper tone, so its noise also turns white
        val red = IntArray(256)
        val green = IntArray(256)
        val blue = IntArray(256)
        var count = 0
        for (i in pixels.indices step step) {
            val p = pixels[i]
            if (abs(luma(p) - paper) <= 6) {
                red[(p shr 16) and 0xFF]++
                green[(p shr 8) and 0xFF]++
                blue[p and 0xFF]++
                count++
            }
        }
        if (count == 0) return null
        val gains = Gains(
            255f / percentile(red, count, 0.9).coerceAtLeast(MIN_PAPER),
            255f / percentile(green, count, 0.9).coerceAtLeast(MIN_PAPER),
            255f / percentile(blue, count, 0.9).coerceAtLeast(MIN_PAPER),
        )

        var coloured = 0
        var sampled = 0
        for (i in pixels.indices step step) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF) * gains.red
            val g = ((p shr 8) and 0xFF) * gains.green
            val b = (p and 0xFF) * gains.blue
            if (maxOf(r, g, b) - minOf(r, g, b) > COLOUR_DISTANCE) coloured++
            sampled++
        }
        if (coloured > sampled * MAX_COLOURED_SHARE) return null
        return gains
    }

    /** Applies [gains] to [pixels] in place. */
    fun apply(pixels: IntArray, gains: Gains) {
        val red = table(gains.red)
        val green = table(gains.green)
        val blue = table(gains.blue)
        for (i in pixels.indices) {
            val p = pixels[i]
            pixels[i] = (p and 0xFF000000.toInt()) or
                (red[(p shr 16) and 0xFF] shl 16) or
                (green[(p shr 8) and 0xFF] shl 8) or
                blue[p and 0xFF]
        }
    }

    /** The most common light tone (histogram peak above mid-grey, slightly smoothed), or `null` if it is too dark. */
    private fun paperTone(histogram: IntArray): Int? {
        var best = -1
        var bestCount = 0
        for (tone in 128..255) {
            var sum = 0
            for (d in -2..2) sum += histogram.getOrElse(tone + d) { 0 }
            if (sum > bestCount) {
                bestCount = sum
                best = tone
            }
        }
        return best.takeIf { it >= MIN_PAPER }
    }

    private fun percentile(histogram: IntArray, total: Int, fraction: Double): Int {
        val target = (total * fraction).toInt()
        var seen = 0
        for (value in histogram.indices) {
            seen += histogram[value]
            if (seen > target) return value
        }
        return 255
    }

    private fun table(gain: Float) = IntArray(256) { (it * gain).roundToInt().coerceAtMost(255) }

    private fun luma(p: Int): Int = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
}
