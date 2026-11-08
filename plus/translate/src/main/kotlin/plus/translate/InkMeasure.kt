package plus.translate

/**
 * Measures the original letters in a text box: how big they are, how many lines, where exactly
 * they sit and their colour. The translation is then set the way the letterer set the original:
 * the same letter size on the whole page, in the same place.
 *
 * Works on plain ARGB pixels so it can be tested without Android.
 */
object InkMeasure {

    class Ink(
        /** Tight box around the letters. */
        val box: Box,
        val lines: Int,
        /**
         * Height of the letters' dense band, in pixels: cap height for capitals, x-height for
         * lower case, the whole character for CJK. For vertical text, the column width.
         */
        val core: Float,
        /** Distance between neighbouring lines (baselines or column centres), `null` for one line. */
        val pitch: Float?,
        /** Average colour of the letters. */
        val color: Int,
    )

    /**
     * Finds the letters around [box] (the model's box is rough, so a little around it too).
     * [lightLetters] for light letters on dark art; [vertical] for columns of CJK text.
     */
    fun measure(
        pixels: IntArray,
        width: Int,
        height: Int,
        box: Box,
        lightLetters: Boolean,
        vertical: Boolean,
        /** The bubble's interior, if known: letters are only inside it (its outline is not a letter). */
        inside: Room? = null,
    ): Ink? {
        // Across the lines the model's box is about right; along them it may cut a letter
        val area = if (vertical) {
            Box(
                left = (box.left - box.width * SEARCH_ALONG).toInt().coerceAtLeast(0),
                top = (box.top - box.height * SEARCH_ACROSS).toInt().coerceAtLeast(0),
                right = (box.right + box.width * SEARCH_ALONG).toInt().coerceAtMost(width),
                bottom = (box.bottom + box.height * SEARCH_ACROSS).toInt().coerceAtMost(height),
            )
        } else {
            Box(
                left = (box.left - box.width * SEARCH_ACROSS).toInt().coerceAtLeast(0),
                top = (box.top - box.height * SEARCH_ALONG).toInt().coerceAtLeast(0),
                right = (box.right + box.width * SEARCH_ACROSS).toInt().coerceAtMost(width),
                bottom = (box.bottom + box.height * SEARCH_ALONG).toInt().coerceAtMost(height),
            )
        }
        if (area.width < 3 || area.height < 3) return null

        // The grid is laid out so that lines run along x: vertical text is transposed
        val along = if (vertical) area.height else area.width
        val across = if (vertical) area.width else area.height
        fun pageX(a: Int, c: Int) = if (vertical) area.left + c else area.left + a
        fun pageY(a: Int, c: Int) = if (vertical) area.top + a else area.top + c
        val letter = BooleanArray(along * across)
        val outline = BooleanArray(along * across)
        for (c in 0 until across) {
            for (a in 0 until along) {
                val x = pageX(a, c)
                val y = pageY(a, c)
                val lum = BubbleEraser.luminance(pixels[y * width + x])
                val isLetter = if (lightLetters) lum >= LIGHT_LETTER else lum < DARK_LETTER
                letter[c * along + a] = isLetter && (inside == null || inside.contains(x, y))
                outline[c * along + a] = lum < OUTLINE
            }
        }
        if (lightLetters) {
            // Light letters on art have a dark outline; light smoke, fire or sky around them don't
            val nearOutline = ArtCleaner.dilate(outline, along, across, OUTLINE_REACH)
            for (i in letter.indices) letter[i] = letter[i] && nearOutline[i]
        }

        // A bubble outline or panel border running across the lines is not a letter
        val crossing = BooleanArray(along) { a ->
            var count = 0
            for (c in 0 until across) if (letter[c * along + a]) count++
            count > across * BORDER_SHARE
        }
        val profile = IntArray(across) { c ->
            var count = 0
            for (a in 0 until along) if (!crossing[a] && letter[c * along + a]) count++
            count
        }
        val peak = profile.maxOrNull() ?: 0
        if (peak < MIN_PEAK) return null
        val threshold = maxOf(2, (peak * LINE_SHARE).toInt())

        // Lines: runs of rows with ink, small gaps (a thin letter part) bridged
        val runs = mutableListOf<IntRange>()
        var start = -1
        var lastInk = -1
        for (c in 0 until across) {
            if (profile[c] >= threshold) {
                if (start < 0) start = c
                lastInk = c
            } else if (start >= 0 && c - lastInk > GAP) {
                runs += start..lastInk
                start = -1
            }
        }
        if (start >= 0) runs += start..lastInk
        if (runs.isEmpty()) return null
        // An outline's curve gives a thin run of its own: much thinner than the text lines
        val tallest = runs.maxOf { it.last - it.first + 1 }
        val lines = runs.filter { it.last - it.first + 1 >= tallest * MIN_LINE_SHARE }

        val cores = lines.map { run ->
            val linePeak = run.maxOf { profile[it] }
            val dense = run.filter { profile[it] >= linePeak * CORE_SHARE }
            (dense.last() - dense.first() + 1).toFloat()
        }.sorted()
        val core = cores[cores.size / 2]
        val pitch = if (lines.size < 2) {
            null
        } else {
            lines.zipWithNext { a, b -> (b.first + b.last) / 2f - (a.first + a.last) / 2f }.sorted()
                .let { it[it.size / 2] }
        }

        var minA = Int.MAX_VALUE
        var maxA = -1
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var count = 0L
        for (run in lines) {
            for (c in run) {
                for (a in 0 until along) {
                    if (crossing[a] || !letter[c * along + a]) continue
                    minA = minOf(minA, a)
                    maxA = maxOf(maxA, a)
                    val color = pixels[pageY(a, c) * width + pageX(a, c)]
                    sumR += (color shr 16) and 0xFF
                    sumG += (color shr 8) and 0xFF
                    sumB += color and 0xFF
                    count++
                }
            }
        }
        if (count == 0L) return null
        val firstC = lines.first().first
        val lastC = lines.last().last
        val inkBox = if (vertical) {
            Box(area.left + firstC, area.top + minA, area.left + lastC + 1, area.top + maxA + 1)
        } else {
            Box(area.left + minA, area.top + firstC, area.left + maxA + 1, area.top + lastC + 1)
        }
        val color = (0xFF shl 24) or
            ((sumR / count).toInt() shl 16) or
            ((sumG / count).toInt() shl 8) or
            (sumB / count).toInt()
        return Ink(inkBox, lines.size, core, pitch, color)
    }

    /** How far along the lines (share of the box) letters are looked for: the model may cut a word. */
    private const val SEARCH_ALONG = 0.05f

    /** And across the lines: the model may miss a whole line. */
    private const val SEARCH_ACROSS = 0.12f

    private const val DARK_LETTER = 110
    private const val LIGHT_LETTER = 190

    /** A light letter's outline: this dark, this close (pixels). */
    private const val OUTLINE = 70
    private const val OUTLINE_REACH = 3

    /** A column with letter pixels on more than this share of the rows is a border, not a letter. */
    private const val BORDER_SHARE = 0.7f

    /** A row belongs to a line when it has at least this share of the fullest row's ink. */
    private const val LINE_SHARE = 0.12f
    private const val MIN_PEAK = 3
    private const val GAP = 1
    private const val MIN_LINE_SHARE = 0.4f

    /** Rows with at least this share of the line's fullest row make the dense band (cap or x-height). */
    private const val CORE_SHARE = 0.4f
}
