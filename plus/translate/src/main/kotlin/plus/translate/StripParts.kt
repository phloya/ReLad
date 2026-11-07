package plus.translate

/**
 * Cuts a long webtoon page into parts for the model. Squeezed whole into the model's image size, a
 * 800 × 14 820 strip became 110 × 2048: the letters were a few pixels tall, the model misread them
 * and put its boxes hundreds of pixels off. Parts keep the page's own width.
 *
 * Cuts go through quiet rows (the plain gaps between panels, where no bubble is), as low as the
 * part's height allows. Where there is no gap the cut goes through the picture and the next part
 * starts a bit higher, so a bubble on the cut is whole in one of the two.
 */
object StripParts {

    /** A page much taller than wide: a webtoon strip. */
    fun isStrip(width: Int, height: Int): Boolean = height > width * STRIP_RATIO

    /**
     * Parts of a [width] × [height] page, as ranges of page rows. [spread] is the brightness spread
     * (max − min) of each row of a smaller copy of the page whose rows are [rowScale] page rows each.
     */
    fun parts(width: Int, height: Int, spread: IntArray, rowScale: Double, maxPartHeight: Int = 0): List<IntRange> {
        if (!isStrip(width, height)) return listOf(0 until height)
        val maxPart = if (maxPartHeight > 0) maxPartHeight else (width * PART_RATIO).toInt()
        val overlap = (maxPart * OVERLAP).toInt()
        val parts = mutableListOf<IntRange>()
        var top = 0
        while (top < height) {
            if (height - top <= maxPart) {
                parts += top until height
                break
            }
            val cut = quietCut(spread, rowScale, top + (maxPart * MIN_FILL).toInt(), top + maxPart)
            if (cut != null) {
                parts += top until cut
                top = cut
            } else {
                parts += top until top + maxPart
                top += maxPart - overlap
            }
        }
        return parts
    }

    /** The lowest quiet place between page rows [from] and [to]: the middle of a run of quiet rows. */
    private fun quietCut(spread: IntArray, rowScale: Double, from: Int, to: Int): Int? {
        val first = (from / rowScale).toInt().coerceIn(0, spread.size - 1)
        val last = (to / rowScale).toInt().coerceIn(0, spread.size - 1)
        var row = last
        while (row >= first) {
            if (spread[row] <= QUIET_SPREAD) {
                var runTop = row
                while (runTop > 0 && spread[runTop - 1] <= QUIET_SPREAD) runTop--
                var runBottom = row
                while (runBottom < spread.size - 1 && spread[runBottom + 1] <= QUIET_SPREAD) runBottom++
                // A run may reach past the window: cut inside the window, as near its middle as it can
                val middle = ((runTop + runBottom) / 2).coerceIn(first, last)
                if (runBottom - runTop + 1 >= MIN_QUIET_ROWS) return (middle * rowScale).toInt().coerceIn(from, to)
                row = runTop - 1
            } else {
                row--
            }
        }
        return null
    }

    /** Brightness spread of each row of [pixels] (ARGB, [width] wide). */
    fun rowSpread(pixels: IntArray, width: Int, height: Int): IntArray = IntArray(height) { y ->
        var min = MAX_LUMINANCE
        var max = 0
        val start = y * width
        for (i in start until start + width) {
            val p = pixels[i]
            val l = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
            if (l < min) min = l
            if (l > max) max = l
        }
        max - min
    }

    /**
     * Regions found in two overlapping parts, once: same text, or boxes mostly over each other.
     * The first one (from the upper part) stays.
     */
    fun withoutRepeats(regions: List<TextRegion>): List<TextRegion> {
        val kept = mutableListOf<TextRegion>()
        for (region in regions) {
            val repeat = kept.any { other ->
                val overlap = overlapShare(region.box, other.box)
                overlap > SAME_PLACE || (overlap > 0f && sameText(region.text, other.text))
            }
            if (!repeat) kept += region
        }
        return kept
    }

    private fun overlapShare(a: Box, b: Box): Float {
        val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (w <= 0 || h <= 0) return 0f
        val smaller = minOf(a.width.toLong() * a.height, b.width.toLong() * b.height).coerceAtLeast(1)
        return (w.toLong() * h).toFloat() / smaller
    }

    private fun sameText(a: String, b: String): Boolean {
        val x = a.filter { it.isLetterOrDigit() }.lowercase()
        val y = b.filter { it.isLetterOrDigit() }.lowercase()
        return x.isNotEmpty() && (x == y || (x.length >= MIN_SHARED && (x in y || y in x)))
    }

    /** A page taller than this many widths is a strip. */
    private const val STRIP_RATIO = 2.5f

    /** A part is at most this many widths tall: letters keep their size in the model's image. */
    private const val PART_RATIO = 2.0f

    /** A cut through quiet rows is looked for in the lower part of this share of the part. */
    private const val MIN_FILL = 0.5f

    /** Where a cut goes through the picture, the next part starts this share of a part higher. */
    private const val OVERLAP = 0.2f
    private const val QUIET_SPREAD = 12
    private const val MIN_QUIET_ROWS = 2
    private const val MAX_LUMINANCE = 255
    private const val SAME_PLACE = 0.6f
    private const val MIN_SHARED = 6
}
