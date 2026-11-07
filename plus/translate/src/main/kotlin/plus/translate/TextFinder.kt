package plus.translate

/**
 * Finds the text on a page from its pixels alone, without the model: letters (small dark shapes
 * on clean paper, or light shapes with a dark edge on the art or on black), joined into lines and
 * the lines into texts. The model says what a page's text reads and about where it is;
 * [TextPlacer] then puts each of its regions onto the text found here, so erasing and lettering
 * don't depend on how well the model drew its boxes.
 *
 * Letters are told from the art by their size, by clean paper (or a dark edge) right around
 * them and by standing in lines with others of their size; a stroke of the bubble's outline or a
 * panel border between two letters keeps them apart.
 *
 * Pure Kotlin on ARGB pixels, so it can be tested without Android.
 */
object TextFinder {

    /** A line of letters (a column, for vertical CJK text). */
    class Line(
        val box: Box,
        /** The median height of its letters (for vertical text, their width), in pixels. */
        val letterHeight: Float,
        /** About how many characters it holds, from its letters' widths. 0 for a line of dots. */
        val chars: Float,
    )

    /** Letters close together with nothing drawn between them: a bubble's, a caption's text. */
    class Text(
        val box: Box,
        /** In reading order of lines: top to bottom; for vertical text, right to left. */
        val lines: List<Line>,
        val letterHeight: Float,
        /** Light letters (with a dark edge, on the art or on black), not dark ones on paper. */
        val light: Boolean,
        /** Letters found, not counting dots and marks. */
        val letters: Int,
        /** Each letter's box (dots and marks too), for checks and tuning. */
        val pieces: List<Box> = emptyList(),
        /**
         * How much it looks like lettering rather than bits of the art, 0–1. Dark letters: the
         * share of paper (or other letters) around the text: a bubble's text has its bubble's
         * paper around it, hatching and faces don't. Light letters: the share of black around
         * them (three letters on a line at least); light letters on the art have art around.
         */
        val quality: Float = 1f,
    ) {
        val chars: Float get() = lines.fold(0f) { sum, line -> sum + line.chars }
    }

    /**
     * The texts on the page. [vertical]: CJK text set in columns; its "lines" are then columns,
     * read from right to left.
     */
    fun find(pixels: IntArray, width: Int, height: Int, vertical: Boolean = false): List<Text> {
        if (width < MIN_PAGE || height < MIN_PAGE) return emptyList()
        // Coloured light (skin, a shirt, the sky in a webtoon) is never paper nor a light letter:
        // it counts as a middle grey
        val lum = ByteArray(width * height) { i ->
            val color = pixels[i]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            val value = BubbleEraser.luminance(color)
            val tinted = maxOf(r, g, b) - minOf(r, g, b) > MAX_CHROMA
            (if (tinted) minOf(value, LIGHT - 1) else value).toByte()
        }
        // Sizes are reckoned on a page's height; a webtoon's long strip is as wide as a page
        val scale = minOf(height.toFloat(), width * PAGE_SHAPE)
        // Paper: plain light areas of some size (a bubble's inside, a page's margin). A letter's
        // counter is small; the light between a screentone's dots or hatching's strokes may be
        // large, but it is cut into short runs on every row
        val runs = Runs()
        val lightParts = scan(lum, width, height, light = true, paper = null, runs = runs)
        val paperSize = maxOf(MIN_PAPER, (scale * scale / PAGE_SHAPE * PAPER_SHARE).toInt())
        val paperRun = maxOf(MIN_PAPER_RUN, width * PAPER_RUN_SHARE)
        val paper = BooleanArray(width * height)
        for (r in 0 until runs.size) {
            val size = lightParts.sizeOf(runs.label[r])
            if (size < paperSize || size < lightParts.runsOf(runs.label[r]) * paperRun) continue
            val row = runs.y[r] * width
            for (x in runs.start[r]..runs.end[r]) paper[row + x] = true
        }
        val darkParts = scan(lum, width, height, light = false, paper = paper, runs = null)
        val dark = pieces(darkParts, scale, light = false)
        // Around a bubble's text: its paper, and the other texts' letters (two texts may stand close)
        val inked = BooleanArray(width * height)
        for (p in dark) {
            for (y in p.top until p.bottom) inked.fill(true, y * width + p.left, y * width + p.right)
        }
        // A dark letter's counter (the hole of an O) is a light shape with a dark edge too
        val light = pieces(lightParts, scale, light = true).filterNot { piece -> dark.any { it.holds(piece) } }
        fun clean(i: Int) = paper[i] || inked[i]

        // Around light letters: black (a caption box, a webtoon's black panel); on the art, it isn't
        fun black(i: Int) = (lum[i].toInt() and 0xFF) < EDGE
        return texts(lum, paper, width, height, dark, light = false, vertical, ::clean) +
            texts(lum, paper, width, height, light, light = true, vertical, ::black)
    }

    /** A connected shape: its box (right and bottom exclusive) and how many pixels it has. */
    private class Piece(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val count: Int,
        /** A dot, a comma, a dash: joins a line of letters, never makes one. */
        val mark: Boolean,
    ) {
        val width get() = right - left
        val height get() = bottom - top

        fun holds(other: Piece) = other.left >= left && other.right <= right && other.top >= top &&
            other.bottom <= bottom && count > other.count
    }

    /**
     * Components of one kind of pixels (dark ink, or light), 8-connected, found row by row as runs.
     * Each keeps its box, size and how many of its runs end at clean paper (dark ink), or at a
     * dark edge (light). [runs], if given, keeps every run, to mark the components' pixels later.
     */
    private fun scan(
        lum: ByteArray,
        width: Int,
        height: Int,
        light: Boolean,
        paper: BooleanArray?,
        runs: Runs?,
    ): Components {
        val parts = Components()
        var prevStart = IntArray(width / 2 + 1)
        var prevEnd = IntArray(width / 2 + 1)
        var prevLabel = IntArray(width / 2 + 1)
        var curStart = IntArray(width / 2 + 1)
        var curEnd = IntArray(width / 2 + 1)
        var curLabel = IntArray(width / 2 + 1)
        var prevCount = 0
        fun on(i: Int): Boolean {
            val v = lum[i].toInt() and 0xFF
            return if (light) v >= LIGHT else v < INK
        }

        // Where a run of a letter ends, light (or for a light letter, its dark edge) is right next
        // to it; for a dark letter, often paper: the light inside an O is not paper, but it is light
        fun clean(i: Int): Boolean {
            val v = lum[i].toInt() and 0xFF
            return if (light) v < EDGE else v >= LIGHT
        }
        fun onPaper(i: Int): Boolean = !light && paper!![i]
        for (y in 0 until height) {
            val row = y * width
            var count = 0
            var k = 0
            var x = 0
            while (x < width) {
                if (!on(row + x)) {
                    x++
                    continue
                }
                val start = x
                while (x < width && on(row + x)) x++
                val end = x - 1
                // Joined to the runs above that touch it, diagonally too
                while (k < prevCount && prevEnd[k] < start - 1) k++
                var label = -1
                var j = k
                while (j < prevCount && prevStart[j] <= end + 1) {
                    val other = parts.find(prevLabel[j])
                    label = if (label < 0) other else parts.union(label, other)
                    j++
                }
                if (label < 0) label = parts.add()
                var ends = 0
                var cleanEnds = 0
                var paperEnds = 0
                if (start > 0) {
                    ends++
                    if ((1..CLEAN_REACH).any { start - it >= 0 && clean(row + start - it) }) cleanEnds++
                    if ((1..CLEAN_REACH).any { start - it >= 0 && onPaper(row + start - it) }) paperEnds++
                }
                if (end < width - 1) {
                    ends++
                    if ((1..CLEAN_REACH).any { end + it < width && clean(row + end + it) }) cleanEnds++
                    if ((1..CLEAN_REACH).any { end + it < width && onPaper(row + end + it) }) paperEnds++
                }
                parts.addRun(label, start, end, y, ends, cleanEnds, paperEnds)
                runs?.add(y, start, end, label)
                if (count == curStart.size) {
                    curStart = curStart.copyOf(count * 2)
                    curEnd = curEnd.copyOf(count * 2)
                    curLabel = curLabel.copyOf(count * 2)
                }
                curStart[count] = start
                curEnd[count] = end
                curLabel[count] = label
                count++
            }
            prevStart = curStart.also { curStart = prevStart }
            prevEnd = curEnd.also { curEnd = prevEnd }
            prevLabel = curLabel.also { curLabel = prevLabel }
            prevCount = count
        }
        parts.finish()
        return parts
    }

    /** The components that look like letters (or dots and marks): their size, shape and clean ends. */
    private fun pieces(parts: Components, scale: Float, light: Boolean): List<Piece> {
        val minHeight = maxOf(MIN_LETTER, (scale * MIN_LETTER_SHARE).toInt())
        val maxHeight = (scale * MAX_LETTER_SHARE).toInt()
        return parts.pieces { left, top, right, bottom, pixels, ends, cleanEnds, paperEnds ->
            val w = right - left
            val h = bottom - top
            val fill = pixels.toFloat() / (w * h)
            val isClean = ends > 0 && cleanEnds >= ends * MIN_CLEAN && (light || paperEnds >= ends * MIN_PAPER_ENDS)
            when {
                !isClean -> null
                h in minHeight..maxHeight && w <= h * MAX_WORD && fill >= MIN_FILL &&
                    (fill <= MAX_FILL || w <= h * BAR) -> Piece(left, top, right, bottom, pixels, mark = false)
                h in MIN_MARK until minHeight && w <= minHeight * MARK_WIDTH && fill >= MIN_MARK_FILL ->
                    Piece(left, top, right, bottom, pixels, mark = true)
                else -> null
            }
        }
    }

    /** Joins letters into texts and each text's letters into lines. */
    private fun texts(
        lum: ByteArray,
        paper: BooleanArray,
        width: Int,
        height: Int,
        pieces: List<Piece>,
        light: Boolean,
        vertical: Boolean,
        clean: (Int) -> Boolean,
    ): List<Text> {
        val letters = pieces.filter { !it.mark }.sortedBy { it.left }
        if (letters.isEmpty()) return emptyList()
        val parent = IntArray(letters.size) { it }
        fun find(i: Int): Int {
            var a = i
            while (parent[a] != a) {
                parent[a] = parent[parent[a]]
                a = parent[a]
            }
            return a
        }
        val reach = letters.maxOf { it.height } * SAME_SIZE * WORD_GAP + 1
        for (i in letters.indices) {
            val a = letters[i]
            var j = i + 1
            while (j < letters.size && letters[j].left <= a.right + reach) {
                val b = letters[j]
                if (find(i) != find(j) && linked(lum, width, a, b, light)) parent[find(j)] = find(i)
                j++
            }
        }
        val found = letters.indices.groupBy(::find).values.map { members -> members.map { letters[it] } }
            .filter { group -> evenSize(group) && (light || onPaper(paper, width, group)) }

        // A piece or two much smaller than the text right next to them is its mark: an apostrophe,
        // the dots after big letters
        // (one after another: the dots of "..." lead up to the word one by one)
        val sizes = found.map { group -> median(group.map { it.height.toFloat() }) }
        val reached = found.map { group -> bounds(group) }.toMutableList()
        val merged = mutableListOf<Piece>()
        val small = mutableSetOf<Int>()
        var joined = true
        while (joined) {
            joined = false
            for (g in found.indices) {
                if (g in small || found[g].size > SMALL_PARTS) continue
                // ...on its line (a short word above a big shout is a text of its own)
                val big = found.indices.firstOrNull { big ->
                    big != g && big !in small && sizes[big] >= sizes[g] * SMALL_SIZE &&
                        near(reached[g], reached[big], sizes[big] * MARK_REACH) &&
                        sameLine(reached[g], reached[big], vertical)
                } ?: continue
                small += g
                reached[big] = Box(
                    minOf(reached[big].left, reached[g].left),
                    minOf(reached[big].top, reached[g].top),
                    maxOf(reached[big].right, reached[g].right),
                    maxOf(reached[big].bottom, reached[g].bottom),
                )
                found[g].forEach { merged += Piece(it.left, it.top, it.right, it.bottom, it.count, mark = true) }
                joined = true
            }
        }
        val groups = found.filterIndexed { g, _ -> g !in small }

        // Dots and marks join the text they sit next to
        val marks = pieces.filter { it.mark } + merged
        val boxes = found.indices.filter { it !in small }.map { reached[it] }
        val heights = groups.map { group -> median(group.map { it.height.toFloat() }) }
        val attached = groups.map { it.toMutableList() }
        for (mark in marks) {
            var best = -1
            var bestDistance = Float.MAX_VALUE
            for (g in groups.indices) {
                val box = boxes[g]
                val margin = heights[g] * MARK_REACH
                val dx = maxOf(box.left - mark.right, mark.left - box.right, 0).toFloat()
                val dy = maxOf(box.top - mark.bottom, mark.top - box.bottom, 0).toFloat()
                if (dx > margin || dy > margin) continue
                val distance = dx + dy
                if (distance < bestDistance) {
                    best = g
                    bestDistance = distance
                }
            }
            if (best >= 0) attached[best] += mark
        }
        return attached.mapIndexed { g, members ->
            val count = groups[g].size
            val lines = lines(members, vertical)
            val box = bounds(members)
            val quality = if (light && (count < MIN_LIGHT_LETTERS || lines.none { it.chars >= MIN_LIGHT_LETTERS })) {
                0f
            } else {
                surroundings(box, heights[g], width, height, clean)
            }
            Text(
                box,
                lines,
                heights[g],
                light,
                count,
                members.map {
                    Box(it.left, it.top, it.right, it.bottom)
                },
                quality,
            )
        }
    }

    /** [a] lies across the line [b] runs along (for vertical text, the column). */
    private fun sameLine(a: Box, b: Box, vertical: Boolean) = if (vertical) {
        minOf(a.right, b.right) > maxOf(a.left, b.left)
    } else {
        minOf(a.bottom, b.bottom) > maxOf(a.top, b.top)
    }

    /** [a] is at most [reach] pixels from [b] across and along. */
    private fun near(a: Box, b: Box, reach: Float): Boolean {
        val dx = maxOf(b.left - a.right, a.left - b.right, 0)
        val dy = maxOf(b.top - a.bottom, a.top - b.bottom, 0)
        return dx <= reach && dy <= reach
    }

    /** The share of clean pixels around [box], as far as half its letters' height. */
    private fun surroundings(box: Box, letterHeight: Float, width: Int, height: Int, clean: (Int) -> Boolean): Float {
        val around = box.grown((letterHeight * RING).toInt() + 1, width, height)
        var all = 0
        var good = 0
        for (y in around.top until around.bottom) {
            val row = y * width
            val inBox = y >= box.top && y < box.bottom
            for (x in around.left until around.right) {
                if (inBox && x >= box.left && x < box.right) continue
                all++
                if (clean(row + x)) good++
            }
        }
        return if (all == 0) 1f else good.toFloat() / all
    }

    /** Letters of a text are about the same size; bits of the art next to each other are not. */
    private fun evenSize(group: List<Piece>): Boolean {
        if (group.size < MIN_EVEN) return true
        val middle = median(group.map { it.height.toFloat() })
        val even = group.count { it.height >= middle * EVEN_LOW && it.height <= middle * EVEN_HIGH }
        return even >= group.size * EVEN_SHARE
    }

    /**
     * A text's letters stand on paper: between them (outside their boxes) the box around them is
     * mostly paper. Bits of art that passed for letters stand in tone or hatching.
     */
    private fun onPaper(paper: BooleanArray, width: Int, group: List<Piece>): Boolean {
        val box = bounds(group)
        val w = box.width
        val letter = BooleanArray(w * box.height)
        for (p in group) {
            for (y in p.top until p.bottom) {
                val row = (y - box.top) * w - box.left
                for (x in p.left until p.right) letter[row + x] = true
            }
        }
        var between = 0
        var onPaper = 0
        for (y in box.top until box.bottom) {
            val row = (y - box.top) * w - box.left
            for (x in box.left until box.right) {
                if (letter[row + x]) continue
                between++
                if (paper[y * width + x]) onPaper++
            }
        }
        return between < MIN_BETWEEN || onPaper >= between * MIN_PAPER_BETWEEN
    }

    /**
     * Two letters of one text: about the same size, side by side on a line (or one under the
     * other, on the next line) with a word's or a line's gap at most, and no line drawn between.
     */
    private fun linked(lum: ByteArray, width: Int, a: Piece, b: Piece, light: Boolean): Boolean {
        val small = minOf(a.height, b.height).toFloat()
        val big = maxOf(a.height, b.height).toFloat()
        if (big > small * SAME_SIZE) return false
        val gapX = maxOf(a.left, b.left) - minOf(a.right, b.right)
        val gapY = maxOf(a.top, b.top) - minOf(a.bottom, b.bottom)
        return when {
            gapX <= 0 && gapY <= 0 -> true
            // On one line: overlapping across it, a word's gap along it
            gapY < 0 && -gapY >= small * LINE_OVERLAP && gapX <= big * WORD_GAP ->
                light || !wall(lum, width, a, b, across = false)
            // On neighbouring lines (or, vertical text, in one column)
            gapX < 0 && gapY <= big * LINE_GAP -> light || !wall(lum, width, a, b, across = true)
            else -> false
        }
    }

    /**
     * A drawn line between two letters: the outline between two bubbles, a panel's border. In
     * the gap between them, a column (or for letters one above the other, a row) that is dark
     * across most of the span they share.
     */
    private fun wall(lum: ByteArray, width: Int, a: Piece, b: Piece, across: Boolean): Boolean {
        if (!across) {
            val from = minOf(a.right, b.right)
            val to = maxOf(a.left, b.left)
            val top = maxOf(a.top, b.top)
            val bottom = minOf(a.bottom, b.bottom)
            for (x in from until to) {
                var dark = 0
                for (y in top until bottom) if ((lum[y * width + x].toInt() and 0xFF) < INK) dark++
                if (dark >= (bottom - top) * WALL) return true
            }
        } else {
            val from = minOf(a.bottom, b.bottom)
            val to = maxOf(a.top, b.top)
            val left = maxOf(a.left, b.left)
            val right = minOf(a.right, b.right)
            for (y in from until to) {
                var dark = 0
                for (x in left until right) if ((lum[y * width + x].toInt() and 0xFF) < INK) dark++
                if (dark >= (right - left) * WALL) return true
            }
        }
        return false
    }

    /** A text's letters in lines (columns for vertical text), in reading order. */
    private fun lines(members: List<Piece>, vertical: Boolean): List<Line> {
        fun from(p: Piece) = if (vertical) p.left else p.top
        fun to(p: Piece) = if (vertical) p.right else p.bottom
        val rows = mutableListOf<MutableList<Piece>>()
        val spans = mutableListOf<IntArray>()
        // Letters first (they make the lines), dots and marks join the line they overlap
        for (piece in members.sortedWith(compareBy({ it.mark }, { from(it) }))) {
            var best = -1
            var bestOverlap = 0f
            for (r in rows.indices) {
                val overlap = (minOf(to(piece), spans[r][1]) - maxOf(from(piece), spans[r][0])).toFloat()
                val need = if (piece.mark) 1f else minOf(to(piece) - from(piece), spans[r][1] - spans[r][0]) * SAME_LINE
                if (overlap >= need && overlap > bestOverlap) {
                    best = r
                    bestOverlap = overlap
                }
            }
            if (best < 0) {
                rows += mutableListOf(piece)
                spans += intArrayOf(from(piece), to(piece))
            } else {
                rows[best] += piece
                if (!piece.mark) {
                    spans[best][0] = minOf(spans[best][0], from(piece))
                    spans[best][1] = maxOf(spans[best][1], to(piece))
                }
            }
        }
        val lines = rows.map { row ->
            val letters = row.filter { !it.mark }
            val size = if (letters.isEmpty()) {
                0f
            } else {
                median(letters.map { (if (vertical) it.width else it.height).toFloat() })
            }
            val chars = letters.fold(0f) { sum, p ->
                val along = (if (vertical) p.height else p.width).toFloat()
                sum + maxOf(1f, along / (size * CHAR_WIDTH))
            }
            Line(bounds(row), size, chars)
        }
        // Lines top to bottom; columns right to left
        return if (vertical) lines.sortedByDescending { it.box.right } else lines.sortedBy { it.box.top }
    }

    private fun bounds(pieces: List<Piece>) = Box(
        pieces.minOf { it.left },
        pieces.minOf { it.top },
        pieces.maxOf { it.right },
        pieces.maxOf { it.bottom },
    )

    private fun median(values: List<Float>): Float = values.sorted()[values.size / 2]

    /**
     * Components of a page's runs, joined as rows are read (union-find). Each label keeps the
     * box and counts of its own runs; they are added up into the label's root at the end.
     */
    private class Components {
        private var parent = IntArray(INITIAL)
        private var left = IntArray(INITIAL)
        private var top = IntArray(INITIAL)
        private var right = IntArray(INITIAL)
        private var bottom = IntArray(INITIAL)
        private var pixels = IntArray(INITIAL)
        private var ends = IntArray(INITIAL)
        private var cleanEnds = IntArray(INITIAL)
        private var paperEnds = IntArray(INITIAL)
        private var runs = IntArray(INITIAL)
        private var size = 0

        fun add(): Int {
            if (size == parent.size) {
                val n = size * 2
                parent = parent.copyOf(n)
                left = left.copyOf(n)
                top = top.copyOf(n)
                right = right.copyOf(n)
                bottom = bottom.copyOf(n)
                pixels = pixels.copyOf(n)
                ends = ends.copyOf(n)
                cleanEnds = cleanEnds.copyOf(n)
                paperEnds = paperEnds.copyOf(n)
                runs = runs.copyOf(n)
            }
            parent[size] = size
            left[size] = Int.MAX_VALUE
            top[size] = Int.MAX_VALUE
            right[size] = -1
            bottom[size] = -1
            return size++
        }

        fun find(label: Int): Int {
            var a = label
            while (parent[a] != a) {
                parent[a] = parent[parent[a]]
                a = parent[a]
            }
            return a
        }

        /** Joins two roots; returns the one left. */
        fun union(a: Int, b: Int): Int {
            if (a == b) return a
            val (root, child) = if (a < b) a to b else b to a
            parent[child] = root
            return root
        }

        fun addRun(label: Int, start: Int, end: Int, y: Int, runEnds: Int, runCleanEnds: Int, runPaperEnds: Int) {
            left[label] = minOf(left[label], start)
            right[label] = maxOf(right[label], end + 1)
            top[label] = minOf(top[label], y)
            bottom[label] = maxOf(bottom[label], y + 1)
            pixels[label] += end - start + 1
            runs[label]++
            ends[label] += runEnds
            cleanEnds[label] += runCleanEnds
            paperEnds[label] += runPaperEnds
        }

        /** Children were joined after their runs were counted: adds them up into their roots. */
        fun finish() {
            for (label in 0 until size) {
                val root = find(label)
                if (root == label) continue
                left[root] = minOf(left[root], left[label])
                top[root] = minOf(top[root], top[label])
                right[root] = maxOf(right[root], right[label])
                bottom[root] = maxOf(bottom[root], bottom[label])
                pixels[root] += pixels[label]
                ends[root] += ends[label]
                cleanEnds[root] += cleanEnds[label]
                paperEnds[root] += paperEnds[label]
                runs[root] += runs[label]
            }
        }

        /** Pixels of the component [label] is in (after [finish]). */
        fun sizeOf(label: Int) = pixels[find(label)]

        /** Runs (rows' stretches) the component [label] is in is made of (after [finish]). */
        fun runsOf(label: Int) = runs[find(label)]

        fun <T> pieces(make: (Int, Int, Int, Int, Int, Int, Int, Int) -> T?): List<T> {
            val result = ArrayList<T>()
            for (label in 0 until size) {
                if (parent[label] != label || right[label] < 0) continue
                make(
                    left[label],
                    top[label],
                    right[label],
                    bottom[label],
                    pixels[label],
                    ends[label],
                    cleanEnds[label],
                    paperEnds[label],
                )
                    ?.let(result::add)
            }
            return result
        }

        private companion object {
            const val INITIAL = 4096
        }
    }

    /** Every run of a scan: its row, first and last pixel and component. */
    private class Runs {
        var y = IntArray(INITIAL)
        var start = IntArray(INITIAL)
        var end = IntArray(INITIAL)
        var label = IntArray(INITIAL)
        var size = 0

        fun add(row: Int, from: Int, to: Int, of: Int) {
            if (size == y.size) {
                y = y.copyOf(size * 2)
                start = start.copyOf(size * 2)
                end = end.copyOf(size * 2)
                label = label.copyOf(size * 2)
            }
            y[size] = row
            start[size] = from
            end[size] = to
            label[size] = of
            size++
        }

        private companion object {
            const val INITIAL = 16384
        }
    }

    private const val MIN_PAGE = 16

    /** Light with channels further apart than this is coloured, not white paper. */
    private const val MAX_CHROMA = 30

    /** A page is about this many times as high as wide. */
    private const val PAGE_SHAPE = 1.42f

    /** How far around a text its paper is looked at, in its letters' heights. */
    private const val RING = 0.3f

    /** Light "letters" count as text when a line has at least this many. */
    private const val MIN_LIGHT_LETTERS = 3

    /** Paper is a light area of at least this many pixels, or this share of the page. */
    private const val MIN_PAPER = 200
    private const val PAPER_SHARE = 0.0004f

    /** Paper's runs are at least this long on average (pixels, or this share of the page width). */
    private const val MIN_PAPER_RUN = 8f
    private const val PAPER_RUN_SHARE = 0.006f

    /** In a text's box, at least this share of what isn't its letters is paper (if there are this many such pixels). */
    private const val MIN_PAPER_BETWEEN = 0.6f
    private const val MIN_BETWEEN = 20

    /** At least this share of a text's letters (of this many or more) are this close to its middle letter height. */
    private const val MIN_EVEN = 3
    private const val EVEN_LOW = 0.6f
    private const val EVEN_HIGH = 1.6f
    private const val EVEN_SHARE = 0.7f

    /** Darker than this is ink (a letter's stroke); this light or lighter, paper. */
    private const val INK = 150
    private const val LIGHT = BubbleEraser.LIGHT_THRESHOLD

    /** A light letter's edge: darker than this. */
    private const val EDGE = 100

    /** Paper (or a light letter's edge) is looked for this many pixels past a run's end (blurred edges). */
    private const val CLEAN_REACH = 3

    /** A letter's runs end at light (a light letter's: at its dark edge) at least this often... */
    private const val MIN_CLEAN = 0.8f

    /** ...and a dark letter's at paper at least this often: a screentone's dots and hatching don't stand on paper. */
    private const val MIN_PAPER_ENDS = 0.4f

    /** A letter is at least this many pixels and this share of the page high... */
    private const val MIN_LETTER = 6
    private const val MIN_LETTER_SHARE = 0.004f

    /** ...and at most this share (a shout is big, a sound effect bigger: it is left to [SfxLetters]). */
    private const val MAX_LETTER_SHARE = 0.06f

    /** Letters joined into a word are at most this many times as wide as high. */
    private const val MAX_WORD = 12

    /** Share of its box a letter's ink fills: at least this; at most that, unless it is a bar (I, !). */
    private const val MIN_FILL = 0.08f
    private const val MAX_FILL = 0.9f
    private const val BAR = 0.4f

    /** Dots, commas and dashes: from this many pixels high, this many letters' heights wide, this full. */
    private const val MIN_MARK = 2
    private const val MARK_WIDTH = 3
    private const val MIN_MARK_FILL = 0.2f

    /** A mark joins a text this many of its letters' heights away at most. */
    private const val MARK_REACH = 0.7f

    /** This many pieces at most, this many times smaller than a text next to them, are its marks. */
    private const val SMALL_PARTS = 3
    private const val SMALL_SIZE = 1.8f

    /** Letters of one text differ in height at most this many times (capitals and dots aside). */
    private const val SAME_SIZE = 2f

    /** Letters on one line overlap across it by this share of the smaller... */
    private const val LINE_OVERLAP = 0.3f

    /** ...and are a word's gap apart at most (share of the bigger letter's height). */
    private const val WORD_GAP = 1.0f

    /** Neighbouring lines are at most this far apart (share of the letters' height). */
    private const val LINE_GAP = 0.9f

    /** A letter joins a line it overlaps by this share of the smaller of the two. */
    private const val SAME_LINE = 0.4f

    /** A character is about this many letter heights wide (with the space after it). */
    private const val CHAR_WIDTH = 0.8f

    /** A column (row) dark over this share of the span two letters share is a drawn line between them. */
    private const val WALL = 0.6f
}
