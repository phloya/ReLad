package plus.translate

/**
 * Erases the original text inside a bubble or caption without touching its outline or the art.
 *
 * The model gives a box around the text. Inside a larger search area, the bubble's light
 * background is flood-filled starting from the light pixels in the box. The letters are the dark
 * spots that light area encloses; the outline and a line between two joined bubbles are not
 * enclosed (they run out to the edge), so they stay. The light area and its letters are painted
 * with the background colour, but only near the text when the bubble is open, so that a caption
 * without an outline on a white page cannot wipe the art around it.
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
        /**
         * Where the translation may go, row by row: the whole interior of a closed bubble; for an
         * open one, its light area near the original letters (no art there to cover).
         */
        val room: Room? = null,
        /** Share of the text box inside the light area's rows: a bubble's text box is almost all inside. */
        val coverage: Float = 0f,
        /**
         * Share of the light area's rows that is light: a bubble is light but for its letters;
         * a light patch of hatched art between its lines is not.
         */
        val fill: Float = 0f,
        /** Where [paint] will paint the background, in page pixels; `null`: nothing to paint. */
        internal val paintArea: Box? = null,
        internal val area: Box? = null,
        /** The bubble's light area and everything it encloses (its letters), in [area]'s pixels. */
        internal val inside: BooleanArray? = null,
    )

    /** Finds and erases in one go: [analyze], then [paint]. */
    fun erase(
        pixels: IntArray,
        width: Int,
        height: Int,
        textBox: Box,
        searchBox: Box,
        paintBox: Box = textBox.expanded(PAINT_MARGIN, width, height),
    ): Result = analyze(pixels, width, height, textBox, searchBox, paintBox).also { paint(pixels, width, it) }

    /**
     * Paints the background over the letters [analyze] found: the bubble's light area and what it
     * encloses. An outline running out to the bubble's edge (between two joined bubbles) stays.
     */
    fun paint(pixels: IntArray, width: Int, result: Result) {
        val paint = result.paintArea ?: return
        val area = result.area ?: return
        val inside = result.inside ?: return
        for (y in maxOf(paint.top, area.top) until minOf(paint.bottom, area.bottom)) {
            val row = (y - area.top) * area.width - area.left
            for (x in maxOf(paint.left, area.left) until minOf(paint.right, area.right)) {
                if (inside[row + x]) pixels[y * width + x] = result.background
            }
        }
    }

    /**
     * Finds the bubble around the text in [textBox] in [pixels] (ARGB, row by row, [width] × [height])
     * without changing them: everything is measured on the original page before anything is erased.
     * [searchBox] limits how far the bubble interior may extend.
     */
    fun analyze(
        pixels: IntArray,
        width: Int,
        height: Int,
        textBox: Box,
        searchBox: Box,
        paintBox: Box = textBox.expanded(PAINT_MARGIN, width, height),
        /**
         * Erased whole only if at least this share of the text box is in the light area: a sign
         * or banner holds drawings too, and its text box may cover only part of its text.
         */
        wholeCoverage: Float = 0f,
        /** Letters are followed past the box along their lines (not on a sign: drawings on it would be too). */
        followLetters: Boolean = true,
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

        // Light areas reached from the text box, each its own component (4-connected). The bubble
        // is the one with most of the box in it: light bits of the art at the box's edge are not
        val boxTop = (textBox.top - area.top).coerceAtLeast(0)
        val boxBottom = (textBox.bottom - area.top).coerceAtMost(areaHeight)
        val boxLeft = (textBox.left - area.left).coerceAtLeast(0)
        val boxRight = (textBox.right - area.left).coerceAtMost(areaWidth)
        val label = IntArray(light.size)
        val stack = IntArrayStack()
        var labels = 0
        var best = 0
        var bestInBox = 0
        for (sy in boxTop until boxBottom) {
            for (sx in boxLeft until boxRight) {
                val seed = sy * areaWidth + sx
                if (!light[seed] || label[seed] != 0) continue
                val id = ++labels
                label[seed] = id
                stack.push(seed)
                var inBox = 0
                while (stack.isNotEmpty()) {
                    val i = stack.pop()
                    val x = i % areaWidth
                    val y = i / areaWidth
                    if (y in boxTop until boxBottom && x in boxLeft until boxRight) inBox++
                    if (x > 0) stack.pushIf(i - 1, light, label, id)
                    if (x < areaWidth - 1) stack.pushIf(i + 1, light, label, id)
                    if (y > 0) stack.pushIf(i - areaWidth, light, label, id)
                    if (y < areaHeight - 1) stack.pushIf(i + areaWidth, light, label, id)
                }
                if (inBox > bestInBox) {
                    best = id
                    bestInBox = inBox
                }
            }
        }
        if (best == 0) return Result(null, WHITE)
        val filled = BooleanArray(light.size) { label[it] == best }

        // The paper's colour: the middle one of each channel (the letters' blended edges would make
        // a mean a shade darker, and a painted patch would show)
        val reds = IntArray(LEVELS)
        val greens = IntArray(LEVELS)
        val blues = IntArray(LEVELS)
        var count = 0
        for (i in filled.indices) {
            if (!filled[i]) continue
            val color = pixels[(area.top + i / areaWidth) * width + area.left + i % areaWidth]
            reds[(color shr 16) and 0xFF]++
            greens[(color shr 8) and 0xFF]++
            blues[color and 0xFF]++
            count++
        }
        val background = (0xFF shl 24) or
            (median(reds, count) shl 16) or
            (median(greens, count) shl 8) or
            median(blues, count)

        // The light area and everything it encloses: the letters (dark spots with light all around).
        // Dark lines that run out of it (the outline, the line between two joined bubbles) are not
        // enclosed: they reach the area's edge through other dark pixels
        val outside = BooleanArray(light.size)
        for (y in 0 until areaHeight) {
            for (x in 0 until areaWidth) {
                if (x != 0 && y != 0 && x != areaWidth - 1 && y != areaHeight - 1) continue
                val i = y * areaWidth + x
                if (!filled[i] && !outside[i]) {
                    outside[i] = true
                    stack.push(i)
                }
            }
        }
        while (stack.isNotEmpty()) {
            val i = stack.pop()
            val x = i % areaWidth
            val y = i / areaWidth
            if (x > 0) stack.pushOutside(i - 1, filled, outside)
            if (x < areaWidth - 1) stack.pushOutside(i + 1, filled, outside)
            if (y > 0) stack.pushOutside(i - areaWidth, filled, outside)
            if (y < areaHeight - 1) stack.pushOutside(i + areaWidth, filled, outside)
        }
        val inside = BooleanArray(light.size) { !outside[it] }

        // The whole light area's extent: did it run into the search limit?
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1
        for (i in filled.indices) {
            if (!filled[i]) continue
            val x = i % areaWidth
            val y = i / areaWidth
            minX = minOf(minX, x)
            maxX = maxOf(maxX, x)
            minY = minOf(minY, y)
            maxY = maxOf(maxY, y)
        }
        // Ran into the search limit somewhere. The page edge is not a limit: a bubble cut by the page
        // edge (as the letterer left it) is closed by it
        val hitsLimit = (minX == 0 && area.left > 0) || (minY == 0 && area.top > 0) ||
            (maxX == areaWidth - 1 && area.right < width) || (maxY == areaHeight - 1 && area.bottom < height)
        // ...but light up to three page edges is the page's own white, not a bubble
        val pageEdges = listOf(
            minX == 0 && area.left == 0,
            minY == 0 && area.top == 0,
            maxX == areaWidth - 1 && area.right == width,
            maxY == areaHeight - 1 && area.bottom == height,
        ).count { it }
        val enclosed = !hitsLimit && pageEdges < 3

        // Light letters on dark art (title pages, narration over a scene): no bubble to erase
        val textArea = (textBox.width.toLong() * textBox.height).coerceAtLeast(1)
        val lightInBox = countLight(light, area, textBox)
        // (mostly dark box, and the light found is smaller than the box: letters, not a bubble).
        // A small spiky bubble among the art is mostly dark in its box too, but it is closed and
        // holds several small dark letters; a light letter holds a counter or two, and big ones
        if (lightInBox < textArea * MIN_LIGHT_SHARE && count < textArea &&
            !(enclosed && holdsLetters(inside, filled, areaWidth, count))
        ) {
            return Result(null, WHITE, onArt = true)
        }

        // An open bubble's light may run on through a gap in its outline (a text written over it) and
        // around it: the outline, lines of the art and a tone's dots are then enclosed by light too.
        // Only letter-like spots are letters: a line much bigger than them is not erased outside the
        // text's box (the outline stays; a letter touching the art is still erased, the art beyond
        // it not), and specks of a tone don't lead the search for letters on across it
        val spots = if (enclosed) null else Spots.of(inside, filled, areaWidth, area, textBox)
        spots?.lines?.let { lines ->
            for (i in inside.indices) {
                if (!lines[i]) continue
                val x = area.left + i % areaWidth
                val y = area.top + i / areaWidth
                if (x !in textBox.left until textBox.right || y !in textBox.top until textBox.bottom) inside[i] = false
            }
        }

        // This text's own part of the bubble, row by row: the run of the inside that holds the
        // text's middle. Two joined bubbles side by side are two runs, split by their shared line
        val anchor = ((textBox.left + textBox.right) / 2 - area.left).coerceIn(0, areaWidth - 1)
        val first = IntArray(areaHeight) { -1 }
        val last = IntArray(areaHeight) { -1 }
        var lobe = 0L
        var lobeLight = 0L
        var top = -1
        var bottom = -1
        var left = Int.MAX_VALUE
        var right = -1
        for (y in 0 until areaHeight) {
            val row = y * areaWidth
            var bestFrom = -1
            var bestTo = -1
            var bestDistance = Int.MAX_VALUE
            var x = 0
            while (x < areaWidth) {
                if (!inside[row + x]) {
                    x++
                    continue
                }
                val from = x
                while (x < areaWidth && inside[row + x]) x++
                val to = x - 1
                val distance = if (anchor in
                    from..to
                ) {
                    0
                } else {
                    minOf(kotlin.math.abs(anchor - from), kotlin.math.abs(anchor - to))
                }
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestFrom = from
                    bestTo = to
                }
            }
            // A run far to the side of the text belongs to something else
            if (bestFrom < 0 || bestDistance > textBox.width) continue
            first[y] = bestFrom
            last[y] = bestTo
            lobe += bestTo - bestFrom + 1
            for (i in bestFrom..bestTo) if (filled[row + i]) lobeLight++
            if (top < 0) top = y
            bottom = y
            left = minOf(left, bestFrom)
            right = maxOf(right, bestTo)
        }
        if (top < 0) return Result(null, WHITE)
        val erased = Box(area.left + left, area.top + top, area.left + right + 1, area.top + bottom + 1)

        var covered = 0L
        for (y in maxOf(textBox.top, area.top + top) until minOf(textBox.bottom, area.top + bottom + 1)) {
            val row = y - area.top
            if (first[row] < 0) continue
            val from = maxOf(textBox.left, area.left + first[row])
            val to = minOf(textBox.right, area.left + last[row] + 1)
            if (to > from) covered += to - from
        }
        val coverage = covered.toFloat() / textArea

        // A closed bubble is erased whole: the model's box is rough and may miss a line of the text.
        // Not too big for the text, though: a light area that size is more likely art than a bubble.
        val whole = enclosed && lobe <= textArea * MAX_BUBBLE_TO_TEXT && coverage >= wholeCoverage
        // Open bubble or white page: only where the letters are, so no white spills onto the art.
        // Letters are looked for a little around the model's rough box (it often misses some) and
        // along their line for as long as it goes on
        val ink = if (whole || !followLetters) {
            null
        } else {
            inkBox(
                pixels,
                width,
                area,
                textBox.expanded(INK_SEARCH, width, height),
                inside,
                filled,
                spots?.specks,
            )
        }
        val paint = if (whole) {
            Box(area.left, area.top, area.right, area.bottom)
        } else {
            // As far as the letters go (followed along and across their lines), a little around them
            ink?.expanded(INK_MARGIN, width, height) ?: paintBox
        }

        val spans = IntArray((bottom - top + 1) * 2) { i ->
            val y = top + i / 2
            val x = if (i % 2 == 0) first[y] else last[y]
            if (x < 0) -1 else area.left + x
        }
        val component = Room.ofRows(area.top + top, spans)
        // An open bubble's light area may run on into the art: text stays near the original letters
        val room = if (whole) component else component.within((ink ?: paint).expanded(OPEN_ROOM, width, height))

        val fill = lobeLight.toFloat() / lobe.coerceAtLeast(1)

        val rows = if (whole) spans else null
        // An open bubble's light area may run on into the art: the text goes only where we painted
        val forText = if (whole) erased else intersect(paint, erased) ?: erased
        return Result(
            forText,
            background,
            enclosed,
            rows,
            room = room,
            coverage = coverage,
            fill = fill,
            paintArea = paint,
            area = area,
            inside = inside,
        )
    }

    private fun median(histogram: IntArray, count: Int): Int {
        var sum = 0
        for (level in histogram.indices) {
            sum += histogram[level]
            if (sum * 2 >= count) return level
        }
        return histogram.size - 1
    }

    /**
     * The light area [filled] (of [count] pixels) encloses several small dark spots: a bubble's
     * letters. Specks of a pixel or two are noise; a spot this big against the area is a letter's
     * counter (the light area is then a light letter itself).
     */
    private fun holdsLetters(inside: BooleanArray, filled: BooleanArray, areaWidth: Int, count: Int): Boolean {
        val seen = BooleanArray(inside.size)
        val stack = IntArrayStack()
        var spots = 0
        for (seed in inside.indices) {
            if (!inside[seed] || filled[seed] || seen[seed]) continue
            seen[seed] = true
            stack.push(seed)
            var size = 0
            while (stack.isNotEmpty()) {
                val i = stack.pop()
                size++
                val x = i % areaWidth
                for (n in intArrayOf(i - 1, i + 1, i - areaWidth, i + areaWidth)) {
                    if (n < 0 || n >= inside.size || (n == i - 1 && x == 0) ||
                        (n == i + 1 && x == areaWidth - 1)
                    ) {
                        continue
                    }
                    if (inside[n] && !filled[n] && !seen[n]) {
                        seen[n] = true
                        stack.push(n)
                    }
                }
            }
            if (size > count * MAX_LETTER_SPOT) return false
            if (size >= MIN_LETTER_SPOT) spots++
        }
        return spots >= MIN_LETTER_SPOTS
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
     * The box around the letters near [textBox]: dark strokes the light area encloses, wherever the
     * model's rough box put them, and further along their line for as long as letters go on.
     */
    private fun inkBox(
        pixels: IntArray,
        width: Int,
        area: Box,
        textBox: Box,
        inside: BooleanArray,
        light: BooleanArray,
        /** Specks not followed (a tone's dots); `null`: every dark spot is. */
        specks: BooleanArray?,
    ): Box? {
        fun letter(x: Int, y: Int): Boolean {
            val i = (y - area.top) * area.width + (x - area.left)
            return inside[i] && !light[i] && (specks == null || !specks[i]) &&
                luminance(pixels[y * width + x]) < LIGHT_THRESHOLD
        }
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = -1
        var bottom = -1
        for (y in maxOf(textBox.top, area.top) until minOf(textBox.bottom, area.bottom)) {
            for (x in maxOf(textBox.left, area.left) until minOf(textBox.right, area.right)) {
                if (letter(x, y)) {
                    left = minOf(left, x)
                    right = maxOf(right, x)
                    top = minOf(top, y)
                    bottom = maxOf(bottom, y)
                }
            }
        }
        if (right < 0) return null
        // Along the line the next letter is at most a word gap away; the next line, a line gap.
        // Then along the lines found again: the next line may be longer
        val gap = ((bottom - top + 1) * LINE_GAP).toInt().coerceIn(MIN_GAP, MAX_GAP)
        repeat(2) {
            var empty = 0
            var x = right + 1
            while (x < area.right && empty <= gap) {
                if ((top..bottom).any { letter(x, it) }) right = x.also { empty = 0 } else empty++
                x++
            }
            empty = 0
            x = left - 1
            while (x >= area.left && empty <= gap) {
                if ((top..bottom).any { letter(x, it) }) left = x.also { empty = 0 } else empty++
                x--
            }
            empty = 0
            var y = bottom + 1
            while (y < area.bottom && empty <= gap) {
                if ((left..right).any { letter(it, y) }) bottom = y.also { empty = 0 } else empty++
                y++
            }
            empty = 0
            y = top - 1
            while (y >= area.top && empty <= gap) {
                if ((left..right).any { letter(it, y) }) top = y.also { empty = 0 } else empty++
                y--
            }
        }
        return Box(left, top, right + 1, bottom + 1)
    }

    /**
     * The dark spots the light area encloses, measured against the letters (the spots in the text
     * box): [lines] much bigger than a letter and as thin (an outline, a line of the art), [specks]
     * much smaller and many together (a tone's dots), each as page-area pixels of [area].
     */
    private class Spots(val lines: BooleanArray, val specks: BooleanArray) {
        companion object {
            fun of(inside: BooleanArray, filled: BooleanArray, areaWidth: Int, area: Box, textBox: Box): Spots? {
                val id = IntArray(inside.size)
                val sizes = ArrayList<Int>()
                val bounds = ArrayList<IntArray>()
                val stack = IntArrayStack()
                for (seed in inside.indices) {
                    if (!inside[seed] || filled[seed] || id[seed] != 0) continue
                    val spot = sizes.size + 1
                    id[seed] = spot
                    stack.push(seed)
                    var size = 0
                    val b = intArrayOf(Int.MAX_VALUE, Int.MAX_VALUE, -1, -1)
                    while (stack.isNotEmpty()) {
                        val i = stack.pop()
                        size++
                        val x = i % areaWidth
                        val y = i / areaWidth
                        b[0] = minOf(b[0], x)
                        b[1] = minOf(b[1], y)
                        b[2] = maxOf(b[2], x)
                        b[3] = maxOf(b[3], y)
                        if (x > 0) stack.pushSpot(i - 1, inside, filled, id, spot)
                        if (x < areaWidth - 1) stack.pushSpot(i + 1, inside, filled, id, spot)
                        if (i >= areaWidth) stack.pushSpot(i - areaWidth, inside, filled, id, spot)
                        if (i + areaWidth < inside.size) stack.pushSpot(i + areaWidth, inside, filled, id, spot)
                    }
                    sizes += size
                    bounds += b
                }
                // The letters: spots of some size within the text box (a little around it: the
                // model's box is rough), not the outline around them. Their typical height and stroke
                // by ink, not by count: many small bits of art beside big letters don't make the
                // letters small
                val near = Box(
                    textBox.left - (textBox.width * INK_SEARCH).toInt() - area.left,
                    textBox.top - (textBox.height * INK_SEARCH).toInt() - area.top,
                    textBox.right + (textBox.width * INK_SEARCH).toInt() - area.left,
                    textBox.bottom + (textBox.height * INK_SEARCH).toInt() - area.top,
                )
                val letters = sizes.indices.filter { k ->
                    val b = bounds[k]
                    sizes[k] >= MIN_LETTER_SPOT && b[0] >= near.left && b[2] < near.right && b[1] >= near.top &&
                        b[3] < near.bottom
                }
                if (letters.isEmpty()) return null
                fun byInk(value: (Int) -> Int): Int {
                    val sorted = letters.sortedBy(value)
                    val half = sorted.sumOf { sizes[it].toLong() } / 2
                    var sum = 0L
                    for (k in sorted) {
                        sum += sizes[k]
                        if (sum >= half) return value(k)
                    }
                    return value(sorted.last())
                }
                val height = byInk { bounds[it][3] - bounds[it][1] + 1 }

                // How thick a spot's strokes are, roughly: its ink against its box's sides. An outline
                // is drawn about as thick as the letters; letters run together with a grainy
                // background into one big spot are much denser, and are erased as before
                fun thickness(k: Int) =
                    sizes[k].toFloat() / (bounds[k][2] - bounds[k][0] + bounds[k][3] - bounds[k][1] + 2)
                val stroke = byInk { (thickness(it) * STROKE_SCALE).toInt() } / STROKE_SCALE.toFloat()
                val line = BooleanArray(sizes.size) { k ->
                    val b = bounds[k]
                    (b[3] - b[1] + 1 > height * LINE_SPAN || b[2] - b[0] + 1 > textBox.width + height * LINE_SPAN) &&
                        thickness(k) <= stroke * LINE_STROKE
                }
                // Specks of a tone: many close together. A few alone are marks ("…", the dot of "!")
                val small = sizes.indices.filter { k ->
                    maxOf(bounds[k][2] - bounds[k][0], bounds[k][3] - bounds[k][1]) + 1 <= height * SPECK_SHARE
                }
                val cell = height.coerceAtLeast(1)
                val grid = HashMap<Long, Int>()
                fun key(gx: Int, gy: Int) = gx.toLong() shl 32 or (gy.toLong() and 0xFFFFFFFF)
                for (k in small) {
                    val g = key((bounds[k][0] + bounds[k][2]) / 2 / cell, (bounds[k][1] + bounds[k][3]) / 2 / cell)
                    grid[g] = (grid[g] ?: 0) + 1
                }
                val speck = BooleanArray(sizes.size)
                for (k in small) {
                    val gx = (bounds[k][0] + bounds[k][2]) / 2 / cell
                    val gy = (bounds[k][1] + bounds[k][3]) / 2 / cell
                    var around = 0
                    for (dy in -1..1) for (dx in -1..1) around += grid[key(gx + dx, gy + dy)] ?: 0
                    speck[k] = around > TONE_SPECKS
                }
                return Spots(
                    BooleanArray(inside.size) { id[it] > 0 && line[id[it] - 1] },
                    BooleanArray(inside.size) { id[it] > 0 && speck[id[it] - 1] },
                )
            }
        }
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

    private const val LEVELS = 256

    /** Pixels are only painted this far around the text box (share of its size). */
    const val PAINT_MARGIN = 0.3f

    /** How far around the model's box letters are looked for in an open bubble (share of its size). */
    private const val INK_SEARCH = 0.25f

    /** Around the letters found, as a share of their box's size. */
    private const val INK_MARGIN = 0.12f

    /** Letters on one line are at most this share of the line's height apart (word gaps)... */
    private const val LINE_GAP = 0.8f

    /** ...within these limits, in pixels. */
    private const val MIN_GAP = 6
    private const val MAX_GAP = 40

    /** In an open bubble, text may take this much more room around the original letters (share of their size). */
    private const val OPEN_ROOM = 0.35f

    /** A text box with less light than this (share) and no bubble around is light text on dark art. */
    private const val MIN_LIGHT_SHARE = 0.45

    /** A small bubble among the art: its light area encloses at least this many dark spots of a few pixels... */
    private const val MIN_LETTER_SPOTS = 3
    private const val MIN_LETTER_SPOT = 4

    /** ...and none this big against the light area (a light letter's counter is). */
    private const val MAX_LETTER_SPOT = 0.2

    /**
     * In an open bubble, a dark spot this many letters tall (or this many letters wider than the
     * text) is a line, not a letter: the outline, the art.
     */
    private const val LINE_SPAN = 3f

    /** ...and one no bigger than this share of a letter's height either way is a speck... */
    private const val SPECK_SHARE = 0.4f

    /**
     * ...of a tone when more than this many specks are around it (within a letter's height): a few
     * alone are marks, "…" or the dot of "!", and are followed like letters.
     */
    private const val TONE_SPECKS = 12

    /** A line is drawn at most this much thicker than the letters' strokes. */
    private const val LINE_STROKE = 2.5f
    private const val STROKE_SCALE = 100

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

        fun pushOutside(i: Int, filled: BooleanArray, outside: BooleanArray) {
            if (!filled[i] && !outside[i]) {
                outside[i] = true
                push(i)
            }
        }

        fun pushIf(i: Int, light: BooleanArray, label: IntArray, id: Int) {
            if (light[i] && label[i] == 0) {
                label[i] = id
                push(i)
            }
        }

        fun pushSpot(i: Int, inside: BooleanArray, filled: BooleanArray, label: IntArray, id: Int) {
            if (inside[i] && !filled[i] && label[i] == 0) {
                label[i] = id
                push(i)
            }
        }

        fun pop(): Int = data[--size]
        fun isEmpty() = size == 0
        fun isNotEmpty() = size > 0
    }
}
