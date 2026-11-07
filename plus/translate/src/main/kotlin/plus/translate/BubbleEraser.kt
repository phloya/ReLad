package plus.translate

/**
 * Erases the original text inside a bubble or caption without touching its outline or the art.
 *
 * The model gives a box around the text. Inside a larger search area, the bubble's light
 * background is flood-filled starting from the light pixels in the box. On every row, the span
 * from the first to the last pixel of that light area is the bubble interior on that row: it
 * covers the text strokes (they sit between light pixels) and stops at the dark outline.
 * Those spans are painted with the background colour, but only near the text box, so that a
 * caption without an outline on a white page cannot wipe the art around it.
 *
 * Works on plain ARGB pixels so it can be tested without Android.
 * TachiyomiAT painted a white rectangle over the text instead, which cut into outlines and art.
 */
object BubbleEraser {

    /** Pixels at least this bright count as bubble background. */
    const val LIGHT_THRESHOLD = 200

    class Result(
        /** The bubble interior found, in page pixels; text is set inside it. `null` if nothing was found. */
        val erased: Box?,
        val background: Int,
        /**
         * The light area ended inside the search area on all sides: a real bubble with an outline.
         * Otherwise it ran into the search limit (white page, broken outline) and is not to be trusted.
         */
        val enclosed: Boolean = false,
        /**
         * The bubble's shape, for setting text that follows it: for each row of [erased] from its top,
         * the interior's first and last x in page pixels (two numbers per row, -1 for a row without).
         * Only for a whole bubble that was erased entirely.
         */
        val rows: IntArray? = null,
        /** Most of the text box is dark: light letters on dark art, no bubble around them. */
        val onArt: Boolean = false,
    )

    /**
     * Erases the text in [textBox] from [pixels] (ARGB, row by row, [width] × [height]) in place.
     * [searchBox] limits how far the bubble interior may extend.
     */
    fun erase(
        pixels: IntArray,
        width: Int,
        height: Int,
        textBox: Box,
        searchBox: Box,
        paintBox: Box = textBox.expanded(PAINT_MARGIN, width, height),
    ): Result {
        val area = Box(
            left = searchBox.left.coerceIn(0, width),
            top = searchBox.top.coerceIn(0, height),
            right = searchBox.right.coerceIn(0, width),
            bottom = searchBox.bottom.coerceIn(0, height),
        )
        val areaWidth = area.width
        val areaHeight = area.height
        if (areaWidth <= 0 || areaHeight <= 0) return Result(null, WHITE)

        val light = BooleanArray(areaWidth * areaHeight)
        for (y in 0 until areaHeight) {
            for (x in 0 until areaWidth) {
                light[y * areaWidth + x] = luminance(pixels[(area.top + y) * width + area.left + x]) >= LIGHT_THRESHOLD
            }
        }

        // Seeds: light pixels inside the text box
        val filled = BooleanArray(light.size)
        val stack = IntArrayStack()
        for (y in (textBox.top - area.top).coerceAtLeast(
            0,
        ) until (textBox.bottom - area.top).coerceAtMost(areaHeight)) {
            for (x in (textBox.left - area.left).coerceAtLeast(0) until
                (textBox.right - area.left).coerceAtMost(areaWidth)) {
                val i = y * areaWidth + x
                if (light[i] && !filled[i]) {
                    filled[i] = true
                    stack.push(i)
                }
            }
        }
        if (stack.isEmpty()) return Result(null, WHITE)

        // Flood fill the light component, 4-connected
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var count = 0L
        while (stack.isNotEmpty()) {
            val i = stack.pop()
            val x = i % areaWidth
            val y = i / areaWidth
            val color = pixels[(area.top + y) * width + area.left + x]
            sumR += (color shr 16) and 0xFF
            sumG += (color shr 8) and 0xFF
            sumB += color and 0xFF
            count++
            if (x > 0) stack.pushIf(i - 1, light, filled)
            if (x < areaWidth - 1) stack.pushIf(i + 1, light, filled)
            if (y > 0) stack.pushIf(i - areaWidth, light, filled)
            if (y < areaHeight - 1) stack.pushIf(i + areaWidth, light, filled)
        }
        val background = (0xFF shl 24) or
            ((sumR / count).toInt() shl 16) or
            ((sumG / count).toInt() shl 8) or
            (sumB / count).toInt()

        // Each row's span of the light component: the bubble interior on that row
        val first = IntArray(areaHeight) { -1 }
        val last = IntArray(areaHeight) { -1 }
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1
        for (y in 0 until areaHeight) {
            for (x in 0 until areaWidth) {
                if (filled[y * areaWidth + x]) {
                    if (first[y] < 0) first[y] = x
                    last[y] = x
                }
            }
            if (first[y] < 0) continue
            minX = minOf(minX, first[y])
            maxX = maxOf(maxX, last[y])
            minY = minOf(minY, y)
            maxY = maxOf(maxY, y)
        }
        val erased = Box(area.left + minX, area.top + minY, area.left + maxX + 1, area.top + maxY + 1)
        val enclosed = minX > 0 && minY > 0 && maxX < areaWidth - 1 && maxY < areaHeight - 1

        // Light letters on dark art (title pages, narration over a scene): no bubble to erase
        val textArea = (textBox.width.toLong() * textBox.height).coerceAtLeast(1)
        val lightInBox = countLight(light, area, textBox)
        // (mostly dark box, and the light found is smaller than the box: letters, not a bubble)
        if (lightInBox < textArea * MIN_LIGHT_SHARE && count < textArea) return Result(null, WHITE, onArt = true)

        // A closed bubble is erased whole: the model's box is rough and may miss a line of the text.
        // Not too big for the text, though: a light area that size is more likely art than a bubble.
        val whole = enclosed && count <= textArea * MAX_BUBBLE_TO_TEXT
        val paint = if (whole) {
            Box(area.left, area.top, area.right, area.bottom)
        } else {
            // Open bubble or white page: only where the letters are, so no white spills onto the art
            // Letters are looked for a little around the model's rough box: it often misses some
            inkBox(pixels, width, area, textBox.expanded(INK_SEARCH, width, height), first, last)
                ?.expanded(INK_MARGIN, width, height)
                ?.let { ink -> intersect(ink, paintBox) } ?: paintBox
        }
        for (y in 0 until areaHeight) {
            if (first[y] < 0) continue
            val pageY = area.top + y
            if (pageY < paint.top || pageY >= paint.bottom) continue
            val row = pageY * width + area.left
            val from = maxOf(first[y], paint.left - area.left)
            val to = minOf(last[y], paint.right - area.left - 1)
            for (x in from..to) pixels[row + x] = background
        }

        val rows = if (whole) {
            IntArray((maxY - minY + 1) * 2) { i ->
                val y = minY + i / 2
                val x = if (i % 2 == 0) first[y] else last[y]
                if (x < 0) -1 else area.left + x
            }
        } else {
            null
        }
        // An open bubble's light area may run on into the art: the text goes only where we painted
        val forText = if (whole) erased else intersect(paint, erased) ?: erased
        return Result(forText, background, enclosed, rows)
    }

    /** Light pixels of [box] inside the search [area]. */
    private fun countLight(light: BooleanArray, area: Box, box: Box): Long {
        var n = 0L
        for (y in maxOf(box.top, area.top) until minOf(box.bottom, area.bottom)) {
            for (x in maxOf(box.left, area.left) until minOf(box.right, area.right)) {
                if (light[(y - area.top) * area.width + (x - area.left)]) n++
            }
        }
        return n
    }

    /**
     * The box around the dark strokes inside [textBox] that sit within the light area's row spans:
     * the letters themselves, wherever the model's rough box put them.
     */
    private fun inkBox(pixels: IntArray, width: Int, area: Box, textBox: Box, first: IntArray, last: IntArray): Box? {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = -1
        var bottom = -1
        for (y in maxOf(textBox.top, area.top) until minOf(textBox.bottom, area.bottom)) {
            val row = y - area.top
            if (first[row] < 0) continue
            for (x in maxOf(textBox.left, area.left + first[row]) until minOf(textBox.right, area.left + last[row])) {
                if (luminance(pixels[y * width + x]) < LIGHT_THRESHOLD) {
                    left = minOf(left, x)
                    right = maxOf(right, x)
                    top = minOf(top, y)
                    bottom = maxOf(bottom, y)
                }
            }
        }
        return if (right < 0) null else Box(left, top, right + 1, bottom + 1)
    }

    private fun intersect(a: Box, b: Box): Box? {
        val box = Box(maxOf(a.left, b.left), maxOf(a.top, b.top), minOf(a.right, b.right), minOf(a.bottom, b.bottom))
        return box.takeIf { it.width > 0 && it.height > 0 }
    }

    fun luminance(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /** Pixels are only painted this far around the text box (share of its size). */
    const val PAINT_MARGIN = 0.3f

    /** How far around the model's box letters are looked for in an open bubble (share of its size). */
    private const val INK_SEARCH = 0.25f

    /** Around the letters found, as a share of their box's size. */
    private const val INK_MARGIN = 0.12f

    /** A text box with less light than this (share) and no bubble around is light text on dark art. */
    private const val MIN_LIGHT_SHARE = 0.45

    /** A closed light area this many times larger than the text box is art, not a bubble. */
    private const val MAX_BUBBLE_TO_TEXT = 16

    private const val WHITE = -0x1

    private class IntArrayStack {
        private var data = IntArray(1024)
        private var size = 0

        fun push(value: Int) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = value
        }

        fun pushIf(i: Int, light: BooleanArray, filled: BooleanArray) {
            if (light[i] && !filled[i]) {
                filled[i] = true
                push(i)
            }
        }

        fun pop(): Int = data[--size]
        fun isEmpty() = size == 0
        fun isNotEmpty() = size > 0
    }
}
