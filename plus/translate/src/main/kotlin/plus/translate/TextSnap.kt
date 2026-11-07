package plus.translate

/**
 * Places to look for a bubble's letters when the model put its box beside them. Cheaper models
 * (Claude Haiku) read the text well but place some boxes off by a line or more; erasing and
 * lettering in the wrong place would leave the original and cover the art.
 *
 * A bubble's text box is mostly light with dark letters in it: boxes of the same size nearby
 * that look like that, nearest first, not on another region's text. [PageLettering] then takes
 * the first one that is really in a bubble. Works on plain ARGB pixels so it can be tested
 * without Android.
 */
object TextSnap {

    fun candidates(pixels: IntArray, width: Int, height: Int, box: Box, others: List<Box>): List<Box> {
        if (box.width < 2 || box.height < 2) return emptyList()
        val reachY = maxOf((box.height * REACH).toInt(), (height * MIN_REACH).toInt())
        val reachX = (box.width * REACH_X).toInt()
        val window = Box(
            (box.left - reachX).coerceAtLeast(0),
            (box.top - reachY).coerceAtLeast(0),
            (box.right + reachX).coerceAtMost(width),
            (box.bottom + reachY).coerceAtMost(height),
        )
        val w = window.width
        val h = window.height
        // Running sums of light and dark pixels in the window: any box's counts in four lookups
        val light = IntArray((w + 1) * (h + 1))
        val dark = IntArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var rowLight = 0
            var rowDark = 0
            for (x in 0 until w) {
                val lum = BubbleEraser.luminance(pixels[(window.top + y) * width + window.left + x])
                if (lum >= BubbleEraser.LIGHT_THRESHOLD) rowLight++
                if (lum < DARK) rowDark++
                val i = (y + 1) * (w + 1) + x + 1
                light[i] = light[i - (w + 1)] + rowLight
                dark[i] = dark[i - (w + 1)] + rowDark
            }
        }
        fun count(sums: IntArray, b: Box): Int {
            val l = b.left - window.left
            val t = b.top - window.top
            val r = b.right - window.left
            val bt = b.bottom - window.top
            return sums[bt * (w + 1) + r] - sums[t * (w + 1) + r] - sums[bt * (w + 1) + l] + sums[t * (w + 1) + l]
        }
        val area = box.width.toFloat() * box.height
        fun looksLikeText(b: Box): Boolean {
            val lightShare = count(light, b) / area
            val darkShare = count(dark, b) / area
            return lightShare >= MIN_LIGHT && darkShare in MIN_DARK..MAX_DARK
        }
        val stepY = maxOf(1, box.height / 4)
        val stepX = maxOf(1, box.width / 8)
        val found = mutableListOf<Pair<Float, Box>>()
        var dy = -reachY
        while (dy <= reachY) {
            var dx = -reachX
            while (dx <= reachX) {
                val candidate = Box(box.left + dx, box.top + dy, box.right + dx, box.bottom + dy)
                val inside = candidate.left >= window.left && candidate.top >= window.top &&
                    candidate.right <= window.right && candidate.bottom <= window.bottom
                if (inside && (dx != 0 || dy != 0) && looksLikeText(candidate) &&
                    others.none { overlaps(candidate, it) }
                ) {
                    found +=
                        (kotlin.math.abs(dy).toFloat() / box.height + kotlin.math.abs(dx).toFloat() / box.width) to
                        candidate
                }
                dx += stepX
            }
            dy += stepY
        }
        return found.sortedBy { it.first }.map { it.second }
    }

    /** More than a third of [a] is inside [b]. */
    private fun overlaps(a: Box, b: Box): Boolean {
        val x = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val y = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return x > 0 && y > 0 && x.toLong() * y * 3 > a.width.toLong() * a.height
    }

    private const val DARK = 110

    /** A bubble's text box: mostly light background... */
    private const val MIN_LIGHT = 0.5f

    /** ...with letters: some dark, but not mostly dark. */
    private const val MIN_DARK = 0.03f
    private const val MAX_DARK = 0.4f

    /** How far a box may be moved: this many of its heights up or down... */
    private const val REACH = 4f

    /** ...but at least this share of the page height. */
    private const val MIN_REACH = 0.05f

    /** And sideways, this share of its width. */
    private const val REACH_X = 0.5f
}
