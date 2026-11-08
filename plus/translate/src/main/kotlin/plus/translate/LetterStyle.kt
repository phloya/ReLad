package plus.translate

/**
 * How the original letters are drawn, measured on their pixels: how thick their strokes are and
 * how much they lean. The translation is then set as heavy and as slanted as the original (a bold
 * italic shout stays a bold italic shout).
 */
object LetterStyle {

    class Style(
        /** Stroke thickness as a share of the letters' height. */
        val stroke: Float,
        /** Lean: how far a letter's top is to the right of its foot, per pixel of height (italic > 0). */
        val slant: Float,
    ) {
        companion object {
            val PLAIN = Style(stroke = 0f, slant = 0f)
        }
    }

    /**
     * [mask]: the letters' pixels in a [width] × [height] grid; [letterHeight]: their height on a
     * line; [lines]: the rows each line of letters takes (the lean is measured line by line).
     */
    fun measure(
        mask: BooleanArray,
        width: Int,
        height: Int,
        letterHeight: Float,
        lines: List<IntRange> = listOf(0 until height),
    ): Style {
        var area = 0
        var border = 0
        for (i in mask.indices) {
            if (!mask[i]) continue
            area++
            val x = i % width
            val y = i / width
            val edge = x == 0 || x == width - 1 || y == 0 || y == height - 1 ||
                !mask[i - 1] || !mask[i + 1] || !mask[i - width] || !mask[i + width]
            if (edge) border++
        }
        if (area < MIN_AREA || letterHeight <= 0f) return Style.PLAIN
        // A stroke of width t and length l has about 2l pixels on its border
        val stroke = 2f * area / border.coerceAtLeast(1) / letterHeight
        val slant = slant(mask, width, height, letterHeight, lines)
        return Style(stroke, if (kotlin.math.abs(slant) < UPRIGHT) 0f else slant)
    }

    /**
     * The lean of the letters' upright strokes. Each stroke is followed down a line row by row (a
     * run of the row joined to the one run below it); the tall, thin ones are a letter's stems and
     * diagonals. Their middle slope is the lean: stems outnumber the diagonals of A, V, W, which
     * lean both ways. Too few strokes (a few letters, blurred ones): the lean that piles the
     * letters' columns up most sharply instead.
     */
    private fun slant(mask: BooleanArray, width: Int, height: Int, letterHeight: Float, lines: List<IntRange>): Float {
        val slopes = mutableListOf<Pair<Float, Int>>()
        for (line in lines) {
            val first = line.first.coerceAtLeast(0)
            val last = line.last.coerceAtMost(height - 1)
            val rows = last - first + 1
            if (rows < MIN_ROWS) continue
            var previous = runs(mask, width, first)
            var chains = previous.map { Chain().apply { add(first, it) } }
            val ended = mutableListOf<Chain>()
            for (y in first + 1..last) {
                val current = runs(mask, width, y)
                val next = current.map { run ->
                    // The one run above it, which has only this run below it: the same stroke
                    val above = previous.indices.filter { overlaps(previous[it], run) }
                    val chain = above.singleOrNull()
                        ?.takeIf { a -> current.count { overlaps(previous[a], it) } == 1 }
                        ?.let { chains[it] }
                    (chain ?: Chain()).apply { add(y, run) }
                }
                chains.filter { c -> next.none { it === c } }.let(ended::addAll)
                previous = current
                chains = next
            }
            ended += chains
            for (chain in ended) {
                if (chain.size < rows * MIN_STEM) continue
                // A bar or a blob, not a stroke
                if (chain.medianWidth() > letterHeight * MAX_STEM_WIDTH) continue
                slopes += -chain.slope() to chain.size
            }
        }
        if (slopes.size < MIN_STEMS) return columnSlant(mask, width, height, lines)
        // The middle slope, longer strokes counting more
        slopes.sortBy { it.first }
        val total = slopes.sumOf { it.second }
        var sum = 0
        for ((slope, weight) in slopes) {
            sum += weight
            if (sum * 2 >= total) return slope.coerceIn(-MAX_SLANT, MAX_SLANT)
        }
        return 0f
    }

    /** A stroke followed down: each row's run middle and width. */
    private class Chain {
        val ys = mutableListOf<Int>()
        val middles = mutableListOf<Float>()
        val widths = mutableListOf<Int>()
        val size get() = ys.size

        fun add(y: Int, run: IntRange) {
            ys += y
            middles += (run.first + run.last) / 2f
            widths += run.last - run.first + 1
        }

        fun medianWidth(): Int = widths.sorted()[widths.size / 2]

        /** Least-squares slope of the middles: x per row down. */
        fun slope(): Float {
            val n = ys.size
            val my = ys.average()
            val mx = middles.average()
            var num = 0.0
            var den = 0.0
            for (k in 0 until n) {
                num += (ys[k] - my) * (middles[k] - mx)
                den += (ys[k] - my) * (ys[k] - my)
            }
            return if (den == 0.0) 0f else (num / den).toFloat()
        }
    }

    private fun runs(mask: BooleanArray, width: Int, y: Int): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var x = 0
        val row = y * width
        while (x < width) {
            if (mask[row + x]) {
                val start = x
                while (x < width && mask[row + x]) x++
                out += start until x
            } else {
                x++
            }
        }
        return out
    }

    private fun overlaps(a: IntRange, b: IntRange) = a.first <= b.last && b.first <= a.last

    /** The lean at which each line's columns pile up most sharply; upright unless a lean clearly does better. */
    private fun columnSlant(mask: BooleanArray, width: Int, height: Int, lines: List<IntRange>): Float {
        val columns = IntArray(width + height)
        fun score(slant: Float): Double {
            var sum = 0.0
            for (line in lines) {
                columns.fill(0)
                val middle = (line.first + line.last) / 2f
                for (y in line) {
                    if (y !in 0 until height) continue
                    // Leaning right, a stroke's top is further right: shift each row back by its height
                    val shift = (slant * (y - middle)).toInt() + height / 2
                    for (x in 0 until width) {
                        if (!mask[y * width + x]) continue
                        val c = x + shift
                        if (c in columns.indices) columns[c]++
                    }
                }
                for (c in columns) sum += c.toDouble() * c
            }
            return sum
        }
        val upright = score(0f)
        if (upright <= 0.0) return 0f
        var bestSlant = 0f
        var best = upright * (1 + TIE)
        var k = -STEPS
        while (k <= STEPS) {
            if (k != 0) {
                val s = k * STEP
                val v = score(s)
                if (v > best) {
                    best = v
                    bestSlant = s
                }
            }
            k++
        }
        return bestSlant
    }

    private const val MIN_AREA = 30

    /** A lean smaller than this is upright lettering drawn by hand. */
    private const val UPRIGHT = 0.1f
    private const val MAX_SLANT = 0.5f

    /** Lines lower than this many rows are too small to follow strokes in. */
    private const val MIN_ROWS = 6

    /** A stroke runs down at least this share of its line... */
    private const val MIN_STEM = 0.5f

    /** ...and is at most this share of the letters' height wide. */
    private const val MAX_STEM_WIDTH = 0.4f

    /** Fewer strokes than this: the columns' way instead. */
    private const val MIN_STEMS = 6
    private const val STEP = 0.04f
    private const val STEPS = 12
    private const val TIE = 0.03
}
