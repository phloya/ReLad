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

        // Paint each row's span of the light component
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1
        for (y in 0 until areaHeight) {
            var first = -1
            var last = -1
            for (x in 0 until areaWidth) {
                if (filled[y * areaWidth + x]) {
                    if (first < 0) first = x
                    last = x
                }
            }
            if (first < 0) continue
            val pageY = area.top + y
            if (pageY >= paintBox.top && pageY < paintBox.bottom) {
                val row = pageY * width + area.left
                val from = maxOf(first, paintBox.left - area.left)
                val to = minOf(last, paintBox.right - area.left - 1)
                for (x in from..to) pixels[row + x] = background
            }
            minX = minOf(minX, first)
            maxX = maxOf(maxX, last)
            minY = minOf(minY, y)
            maxY = maxOf(maxY, y)
        }
        val erased = Box(area.left + minX, area.top + minY, area.left + maxX + 1, area.top + maxY + 1)
        val enclosed = minX > 0 && minY > 0 && maxX < areaWidth - 1 && maxY < areaHeight - 1
        return Result(erased, background, enclosed)
    }

    fun luminance(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /** Pixels are only painted this far around the text box (share of its size). */
    const val PAINT_MARGIN = 0.15f

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
