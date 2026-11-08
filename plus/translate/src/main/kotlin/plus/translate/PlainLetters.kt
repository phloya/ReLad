package plus.translate

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Letters set on a plain background of any colour: a tinted or dark bubble, a game-like system
 * window, a black caption box, a gradient or glow behind them. [BubbleEraser] knows only light
 * bubbles; here nothing is assumed about either colour.
 *
 * The background is the large smooth area around the model's box: neighbouring pixels of nearly
 * the same colour, however slowly that colour changes across it (a gradient stays one area). The
 * letters are what that area encloses: their fill, their outline or glow. Their colours, outline,
 * stroke and slant are measured so the translation can be drawn the way the original was, and the
 * background is drawn back over them ([Inpaint]) instead of white.
 *
 * Pure Kotlin on ARGB pixels, so it can be tested without Android.
 */
object PlainLetters {

    class Letters(
        /** The part of the page [mask] covers. */
        val area: Box,
        /** Pixels of [area], row by row, to erase: the letters with their outline or glow. */
        val mask: BooleanArray,
        /** Tight box around the letters. */
        val box: Box,
        /** The background's typical colour. */
        val background: Int,
        /** The letters' own colour (their fill). */
        val fill: Int,
        /** An outline of another colour around the letters, or `null`. */
        val edge: Int?,
        /** The outline's thickness, pixels. */
        val edgeWidth: Float,
        /** The letters' height on a line (cap or x-height), pixels. */
        val letterHeight: Float,
        val lines: Int,
        /** Distance between lines, `null` for one line. */
        val pitch: Float?,
        val style: LetterStyle.Style,
        /** What to erase, in [area]'s pixels: the letters and the glow around them. */
        val erase: BooleanArray,
        /** A soft glow of this colour around the letters (not a drawn outline), or `null`. */
        val glow: Int?,
        /** How far the glow reaches from the letters, pixels. */
        val glowWidth: Float,
        /**
         * Share of the pixels of the letters' colour near them that were found as letters: below
         * nearly all, part of the text merged into the background (glow on a texture) and erasing
         * the rest would leave a half.
         */
        val whole: Float,
        /** The background's extent: a window or box the letters are in, or the area looked at. */
        val room: Box,
        /** The background around the letters is one colour: painted with it, not drawn on from around. */
        val flat: Boolean,
        /** The background changes only slowly (a gradient, a glow): filled smoothly, no texture to copy. */
        val smooth: Boolean,
        /** Pixels of [area] that are the background: what a fill takes its colours from. */
        val backgroundMask: BooleanArray,
    )

    /** The letters around [box] if it is on a plain background, or `null`. */
    fun find(pixels: IntArray, width: Int, height: Int, box: Box, others: List<Box> = emptyList()): Letters? {
        if (box.width < MIN_BOX || box.height < MIN_BOX) return null
        val margin = maxOf(MIN_MARGIN, (minOf(box.width, box.height) * MARGIN).toInt())
        // The background is what lies around the box; or, when that finds nothing (a dark bubble on
        // a white page, the box reaching past it), what fills the box
        var byInside = false
        val first = scan(pixels, width, height, box, box.grown(margin, width, height), others)
            ?: scan(pixels, width, height, box, box.grown(margin, width, height), others, byInside = true)
                ?.also { byInside = true }
            ?: return null
        // Once the letters' size is known, again further around: the model's box may have missed a
        // line, or a line's end (along a line further than across)
        val limit = maxOf(box.width, box.height) * MAX_REACH
        val across = minOf(first.letterHeight * REACH, limit).toInt()
        val along = minOf(first.letterHeight * REACH_ALONG, limit).toInt()
        if (across <= margin && along <= margin) return first
        val area = Box(
            (box.left - maxOf(along, margin)).coerceAtLeast(0),
            (box.top - maxOf(across, margin)).coerceAtLeast(0),
            (box.right + maxOf(along, margin)).coerceAtMost(width),
            (box.bottom + maxOf(across, margin)).coerceAtMost(height),
        )
        return scan(pixels, width, height, box, area, others, byInside) ?: first
    }

    private fun scan(
        pixels: IntArray,
        width: Int,
        height: Int,
        box: Box,
        area: Box,
        others: List<Box>,
        byInside: Boolean = false,
    ): Letters? {
        val w = area.width
        val h = area.height
        val n = w * h
        val color = IntArray(n) { pixels[(area.top + it / w) * width + area.left + it % w] }

        // Smooth areas: neighbours of nearly the same colour. The background is the one with most
        // of the area around the box (outside it), and covers much of the area
        val label = IntArray(n) { -1 }
        val stack = IntArray(n)
        var labels = 0
        var best = -1
        var bestAround = 0
        var bestSize = 0
        var bestInside = 0
        for (seed in 0 until n) {
            if (label[seed] >= 0) continue
            val id = labels++
            label[seed] = id
            var top = 0
            stack[top++] = seed
            var around = 0
            var size = 0
            while (top > 0) {
                val i = stack[--top]
                size++
                val x = i % w
                val y = i / w
                val px = area.left + x
                val py = area.top + y
                if (px < box.left || px >= box.right || py < box.top || py >= box.bottom) around++
                if (x > 0 && label[i - 1] < 0 && near(color[i], color[i - 1])) {
                    label[i - 1] = id
                    stack[top++] = i - 1
                }
                if (x < w - 1 && label[i + 1] < 0 && near(color[i], color[i + 1])) {
                    label[i + 1] = id
                    stack[top++] = i + 1
                }
                if (y > 0 && label[i - w] < 0 && near(color[i], color[i - w])) {
                    label[i - w] = id
                    stack[top++] = i - w
                }
                if (y < h - 1 && label[i + w] < 0 && near(color[i], color[i + w])) {
                    label[i + w] = id
                    stack[top++] = i + w
                }
            }
            val inside = size - around
            if (if (byInside) inside > bestInside else around > bestAround) {
                best = id
                bestAround = around
                bestSize = size
                bestInside = inside
            }
        }
        if (best < 0 || bestSize < n * MIN_BACKGROUND) return null
        // (filling the box: most of it, the letters in it the rest)
        if (byInside && bestInside < box.width.toLong() * box.height * MIN_FILLING) return null
        val background = BooleanArray(n) { label[it] == best }
        val labelSize = IntArray(labels)
        for (l in label) labelSize[l]++

        // The letters: everything the background encloses (what reaches the area's edge through
        // other pixels is art running out of it)
        val outside = BooleanArray(n)
        var top = 0
        for (i in 0 until n) {
            val x = i % w
            val y = i / w
            if ((x == 0 || y == 0 || x == w - 1 || y == h - 1) && !background[i]) {
                outside[i] = true
                stack[top++] = i
            }
        }
        while (top > 0) {
            val i = stack[--top]
            val x = i % w
            for (j in intArrayOf(i - 1, i + 1, i - w, i + w)) {
                if (j < 0 || j >= n || (j == i - 1 && x == 0) || (j == i + 1 && x == w - 1)) continue
                if (!background[j] && !outside[j]) {
                    outside[j] = true
                    stack[top++] = j
                }
            }
        }
        val mask = BooleanArray(n) { !background[it] && !outside[it] }
        if (others.isNotEmpty()) dropOthers(mask, w, h, area, box, others)
        keepText(mask, color, w, h, area, box, median(color, background))
        var inkCount = 0
        var left = Int.MAX_VALUE
        var right = -1
        var topY = Int.MAX_VALUE
        var bottomY = -1
        for (i in 0 until n) {
            if (!mask[i]) continue
            inkCount++
            val x = i % w
            val y = i / w
            left = minOf(left, x)
            right = maxOf(right, x)
            topY = minOf(topY, y)
            bottomY = maxOf(bottomY, y)
        }
        if (inkCount < MIN_INK) return null
        val inkBox = Box(area.left + left, area.top + topY, area.left + right + 1, area.top + bottomY + 1)
        // The letters are about where the model said, and are letters, not the whole box filled
        val boxArea = box.width.toLong() * box.height
        if (overlap(inkBox, box) < boxArea * MIN_ON_BOX &&
            overlap(inkBox, box) < inkBox.width.toLong() * inkBox.height * MIN_ON_BOX
        ) {
            return null
        }
        val share = inkCount.toFloat() / (inkBox.width.toLong() * inkBox.height)
        if (share < MIN_SHARE || share > MAX_SHARE) return null

        // Colours: the background's middle colour; the letters' pixels in two groups, the one at their
        // border (next to the background) is the outline when it differs enough from the other
        val bg = median(color, background)
        val border = BooleanArray(n)
        for (i in 0 until n) {
            if (!mask[i]) continue
            val x = i % w
            val y = i / w
            border[i] = (x > 0 && background[i - 1]) || (x < w - 1 && background[i + 1]) ||
                (y > 0 && background[i - w]) || (y < h - 1 && background[i + w])
        }
        // The letters must stand out from the background: a soft shade within it is not letters
        val inkColor = median(color, mask)
        if (distance(inkColor, bg) < MIN_CONTRAST && maxDistance(color, mask, bg) < MIN_CONTRAST * 2) return null
        val (fillGroup, edgeGroup) = twoGroups(color, mask, border, w, h)
        var fill = inkColor
        var edge: Int? = null
        var edgeWidth = 0f
        // A glow found among the letters' own pixels (it stood out of the background step by step)
        var innerGlow: Int? = null
        var innerGlowWidth = 0f
        if (edgeGroup != null) {
            val (inner, innerCount) = fillGroup
            val (outer, outerCount) = edgeGroup
            when {
                // Thin letters are mostly border: the group nearer the background is their blurred edge
                distance(inner, bg) < MIN_CONTRAST -> fill = outer
                // Shading inside bright letters: a colour on the way from them to the background
                between(inner, outer, bg) -> fill = outer
                // An outline stands out from both the letters and the background; a colour on the way
                // from the letters to the background is their blur, or when thick, a glow
                distance(inner, outer) >= MIN_EDGE_CONTRAST && distance(outer, bg) >= MIN_EDGE_CONTRAST &&
                    outerCount >= inkCount * MIN_EDGE_SHARE && innerCount >= inkCount * MIN_EDGE_SHARE -> {
                    val thickness = outerCount.toFloat() / border.count { it }.coerceAtLeast(1)
                    fill = inner
                    if (!between(outer, inner, bg)) {
                        edge = outer
                        edgeWidth = thickness
                    } else if (thickness >= MIN_GLOW) {
                        innerGlow = outer
                        innerGlowWidth = thickness
                    }
                }
                distance(outer, bg) > distance(inner, bg) -> fill = outer
                else -> fill = inner
            }
        }

        // The fill's own pixels give the letters' shape: lines, size, stroke and slant (cut halfway
        // between the fill and what is around it, so a blurred edge counts the same on any page)
        val around = edge ?: bg
        val shape = BooleanArray(n) { mask[it] && distance(color[it], fill) < distance(color[it], around) }
        val rows = IntArray(h) { y -> (0 until w).count { shape[y * w + it] } }
        val lines = lineRuns(rows)
        if (lines.isEmpty()) return null
        val letterHeight = lines.map { (it.last - it.first + 1).toFloat() }.sorted()[lines.size / 2]
        // Letters taller than the model's box: a blob of glow or art, not a line of text
        if (letterHeight > box.height * MAX_HEIGHT) return null
        val pitch = if (lines.size < 2) {
            null
        } else {
            lines.zipWithNext { a, b -> (b.first + b.last) / 2f - (a.first + a.last) / 2f }.sorted()[lines.size / 2 - 1]
        }
        val style = LetterStyle.measure(shape, w, h, letterHeight, lines)

        // A glow: the background near the letters lit towards their colour, compared with the
        // background a little further out (a gradient changes across the whole area)
        val steps = distances(mask, w, h)
        val reach = maxOf(MIN_HALO, letterHeight * HALO) * CHAMFER
        val ring = BooleanArray(n) { background[it] && steps[it] > 2 * reach && steps[it] <= 3 * reach }
        val local = if (ring.any { it }) median(color, ring) else bg
        val flat = spread(color, if (ring.any { it }) ring else background) <= FLAT
        val smooth = roughness(color, if (ring.any { it }) ring else background, w) <= SMOOTH
        // Followed out from the letters while it differs from the background: a glow (towards the
        // letters' colour; it fades slowly, and what is left of it would show the original's shape)
        // or an outline that runs smoothly into the background (white around black on a grey bubble)
        val halo = BooleanArray(n)
        var top2 = 0
        for (i in 0 until n) if (mask[i]) stack[top2++] = i
        while (top2 > 0) {
            val i = stack[--top2]
            val x = i % w
            for (j in intArrayOf(i - 1, i + 1, i - w, i + w)) {
                if (j < 0 || j >= n || (j == i - 1 && x == 0) || (j == i + 1 && x == w - 1)) continue
                if (halo[j] || mask[j] || outside[j] || steps[j] > 2 * reach) continue
                if (distance(color[j], local) < HALO_CONTRAST) continue
                halo[j] = true
                stack[top2++] = j
            }
        }
        val borderCount = border.count { it }.coerceAtLeast(1)
        // Next to the letters: lit towards their colour (a glow) or away from it (an outline)
        val toward = BooleanArray(n) { halo[it] && distance(color[it], fill) < distance(local, fill) }
        val away = BooleanArray(n) { halo[it] && !toward[it] }
        val glowWidth = toward.count { it }.toFloat() / borderCount
        val awayWidth = away.count { it }.toFloat() / borderCount
        var glow: Int? = null
        if (edge == null && awayWidth >= MIN_OUTLINE && awayWidth >= glowWidth) {
            median(color, BooleanArray(n) { away[it] && steps[it] <= reach / 2 })
                .takeIf { distance(it, local) >= MIN_GLOW_CONTRAST }
                ?.let {
                    edge = it
                    edgeWidth = awayWidth
                }
        }
        if (edge == null && glowWidth >= MIN_GLOW) {
            // Its colour next to the letters (its faint tail is most of it)
            glow = median(color, BooleanArray(n) { toward[it] && steps[it] <= reach / 2 })
                .takeIf { distance(it, local) >= MIN_GLOW_CONTRAST }
        }
        var glowReach = glowWidth
        if (glow == null && edge == null && innerGlow != null) {
            glow = innerGlow
            glowReach = innerGlowWidth
        }
        // (and a little around: a letter's last blurred edge and the noise of a JPEG around it,
        // which would show as the original's ghost)
        val erase = BooleanArray(n) { mask[it] || halo[it] || (!outside[it] && steps[it] <= reach / 2) }

        // Pixels of the letters' colour near them that were not found as letters
        val near = (letterHeight * HALO).toInt()
        // (the background's reach in each row and column: what is beyond it is around the background,
        // not in it)
        val rowFirst = IntArray(h) { w }
        val rowLast = IntArray(h) { -1 }
        val colFirst = IntArray(w) { h }
        val colLast = IntArray(w) { -1 }
        for (i in 0 until n) {
            if (!background[i]) continue
            val x = i % w
            val y = i / w
            rowFirst[y] = minOf(rowFirst[y], x)
            rowLast[y] = maxOf(rowLast[y], x)
            colFirst[x] = minOf(colFirst[x], y)
            colLast[x] = maxOf(colLast[x], y)
        }
        var like = 0
        var found = 0
        for (y in maxOf(0, inkBox.top - area.top - near) until minOf(h, inkBox.bottom - area.top + near)) {
            for (x in maxOf(0, inkBox.left - area.left - near) until minOf(w, inkBox.right - area.left + near)) {
                val i = y * w + x
                if (distance(color[i], fill) * 2 > distance(fill, bg)) continue
                // (a large plain area of the letters' colour beyond the background is no letter
                // missed: the white page around a dark bubble)
                if (labelSize[label[i]] >= letterHeight * letterHeight * BIG_PLAIN &&
                    (x < rowFirst[y] || x > rowLast[y] || y < colFirst[x] || y > colLast[x])
                ) {
                    continue
                }
                like++
                if (mask[i]) found++
            }
        }
        val whole = if (like == 0) 1f else found.toFloat() / like

        var roomLeft = w
        var roomTop = h
        var roomRight = 0
        var roomBottom = 0
        for (i in 0 until n) {
            if (!background[i]) continue
            roomLeft = minOf(roomLeft, i % w)
            roomRight = maxOf(roomRight, i % w + 1)
            roomTop = minOf(roomTop, i / w)
            roomBottom = maxOf(roomBottom, i / w + 1)
        }
        val room = Box(area.left + roomLeft, area.top + roomTop, area.left + roomRight, area.top + roomBottom)
        return Letters(
            area, mask, inkBox, bg, fill, edge, edgeWidth, letterHeight, lines.size, pitch, style,
            erase, glow, if (glow != null) glowReach else 0f, whole, room, flat, smooth, background,
        )
    }

    /** The average step between neighbouring set pixels (largest channel): a texture's or a screen's lines are rough. */
    private fun roughness(color: IntArray, set: BooleanArray, w: Int): Float {
        var sum = 0L
        var count = 0
        for (i in color.indices) {
            if (!set[i]) continue
            for (j in intArrayOf(i + 1, i + w)) {
                if (j >= color.size || !set[j] || (j == i + 1 && j % w == 0)) continue
                val a = color[i]
                val b = color[j]
                sum += maxOf(
                    abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)),
                    abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)),
                    abs((a and 0xFF) - (b and 0xFF)),
                )
                count++
            }
        }
        return if (count == 0) 0f else sum.toFloat() / count
    }

    /** The widest spread of a colour channel over the set pixels (10th to 90th percentile). */
    private fun spread(color: IntArray, set: BooleanArray): Int {
        var widest = 0
        for (shift in intArrayOf(16, 8, 0)) {
            val counts = IntArray(LEVELS)
            var total = 0
            for (i in color.indices) {
                if (!set[i]) continue
                counts[(color[i] shr shift) and 0xFF]++
                total++
            }
            if (total == 0) return 0
            var low = -1
            var high = -1
            var sum = 0
            for (level in counts.indices) {
                sum += counts[level]
                if (low < 0 && sum * 10 >= total) low = level
                if (high < 0 && sum * 10 >= total * 9) high = level
            }
            widest = maxOf(widest, high - low)
        }
        return widest
    }

    /** Takes out of [mask] the pieces that lie in another text's box rather than in [box]. */
    private fun dropOthers(mask: BooleanArray, w: Int, h: Int, area: Box, box: Box, others: List<Box>) {
        val seen = BooleanArray(mask.size)
        val piece = IntArray(mask.size)
        for (seed in mask.indices) {
            if (!mask[seed] || seen[seed]) continue
            var size = 0
            var top = 0
            piece[top++] = seed
            seen[seed] = true
            var count = 0
            var own = 0
            var theirs = 0
            while (top > size) {
                val i = piece[size++]
                val x = i % w
                val y = i / w
                val px = area.left + x
                val py = area.top + y
                count++
                if (px >= box.left && px < box.right && py >= box.top && py < box.bottom) own++
                if (others.any { px >= it.left && px < it.right && py >= it.top && py < it.bottom }) theirs++
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                        val j = ny * w + nx
                        if (mask[j] && !seen[j]) {
                            seen[j] = true
                            piece[top++] = j
                        }
                    }
                }
            }
            if (theirs >= count * THEIRS && own < count * MIN_OWN) {
                for (k in 0 until size) mask[piece[k]] = false
            }
        }
    }

    /**
     * Keeps the pieces of [mask] that make up the text in [box]: those in it and, piece by piece,
     * the ones next to them along a line or on the next line (a word or a line the box missed). An
     * icon or a drawing further off on the same background is not the text.
     */
    private fun keepText(mask: BooleanArray, color: IntArray, w: Int, h: Int, area: Box, box: Box, background: Int) {
        val piece = IntArray(mask.size) { -1 }
        val bounds = mutableListOf<IntArray>()
        val queue = IntArray(mask.size)
        for (seed in mask.indices) {
            if (!mask[seed] || piece[seed] >= 0) continue
            val id = bounds.size
            val b = intArrayOf(w, h, 0, 0)
            var head = 0
            var tail = 0
            queue[tail++] = seed
            piece[seed] = id
            while (head < tail) {
                val i = queue[head++]
                val x = i % w
                val y = i / w
                b[0] = minOf(b[0], x)
                b[1] = minOf(b[1], y)
                b[2] = maxOf(b[2], x + 1)
                b[3] = maxOf(b[3], y + 1)
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                        val j = ny * w + nx
                        if (mask[j] && piece[j] < 0) {
                            piece[j] = id
                            queue[tail++] = j
                        }
                    }
                }
            }
            bounds += b
        }
        if (bounds.isEmpty()) return
        val left = box.left - area.left
        val top = box.top - area.top
        val right = box.right - area.left
        val bottom = box.bottom - area.top
        fun touches(b: IntArray, pad: Int) =
            b[0] < right + pad && b[2] > left - pad && b[1] < bottom + pad && b[3] > top - pad
        // The letters' size: the middle height of the pieces in the box
        val heights = bounds.filter { touches(it, 0) }.map { it[3] - it[1] }.filter { it >= MIN_PIECE }.sorted()
        if (heights.isEmpty()) return
        val size = heights[heights.size / 2].toFloat()
        // The letters' colour: a piece nearer the background's (a speck of a screen's lines, a
        // shade) is not one of them
        val inBox = BooleanArray(mask.size) { mask[it] && touches(bounds[piece[it]], 0) }
        val ink = median(color, inBox)
        // (by its pixels, not its mean colour: an O's hole is the background's colour, and a big hole
        // makes the mean of the letter and its hole nearer the background's)
        val inky = IntArray(bounds.size)
        val count = IntArray(bounds.size)
        for (i in mask.indices) {
            if (!mask[i]) continue
            count[piece[i]]++
            if (distance(color[i], ink) < distance(color[i], background)) inky[piece[i]]++
        }
        val letterLike = BooleanArray(bounds.size) { inky[it] >= count[it] * MIN_INKY }
        val keep = BooleanArray(bounds.size)
        val next = ArrayDeque<Int>()
        val pad = (size * SEED_PAD).toInt()
        for (k in bounds.indices) {
            if (letterLike[k] && touches(bounds[k], pad)) {
                keep[k] = true
                next += k
            }
        }
        while (next.isNotEmpty()) {
            val a = bounds[next.removeFirst()]
            for (m in bounds.indices) {
                if (keep[m] || !letterLike[m]) continue
                val b = bounds[m]
                val gapX = maxOf(0, b[0] - a[2], a[0] - b[2])
                val gapY = maxOf(0, b[1] - a[3], a[1] - b[3])
                if (gapX <= size * CHAIN_ALONG && gapY <= size * CHAIN_ACROSS) {
                    keep[m] = true
                    next += m
                }
            }
        }
        for (i in mask.indices) if (mask[i] && !keep[piece[i]]) mask[i] = false
    }

    /** Distance from the nearest set pixel of [mask], in thirds of a pixel (3–4 chamfer). */
    private fun distances(mask: BooleanArray, w: Int, h: Int): IntArray {
        val far = Int.MAX_VALUE / 4
        val d = IntArray(mask.size) { if (mask[it]) 0 else far }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                var v = d[i]
                if (x > 0) v = minOf(v, d[i - 1] + 3)
                if (y > 0) {
                    v = minOf(v, d[i - w] + 3)
                    if (x > 0) v = minOf(v, d[i - w - 1] + 4)
                    if (x < w - 1) v = minOf(v, d[i - w + 1] + 4)
                }
                d[i] = v
            }
        }
        for (y in h - 1 downTo 0) {
            for (x in w - 1 downTo 0) {
                val i = y * w + x
                var v = d[i]
                if (x < w - 1) v = minOf(v, d[i + 1] + 3)
                if (y < h - 1) {
                    v = minOf(v, d[i + w] + 3)
                    if (x < w - 1) v = minOf(v, d[i + w + 1] + 4)
                    if (x > 0) v = minOf(v, d[i + w - 1] + 4)
                }
                d[i] = v
            }
        }
        return d
    }

    /** [x] is a colour on the way from [a] to [b] (a blur or shade of the two). */
    private fun between(x: Int, a: Int, b: Int) = distance(a, x) + distance(x, b) <= distance(a, b) * BETWEEN

    /** Neighbouring pixels of one smooth area: each channel within a small step. */
    private fun near(a: Int, b: Int): Boolean =
        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) <= STEP &&
            abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) <= STEP &&
            abs((a and 0xFF) - (b and 0xFF)) <= STEP

    fun distance(a: Int, b: Int): Float {
        val r = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val g = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val bl = (a and 0xFF) - (b and 0xFF)
        return sqrt((r * r + g * g + bl * bl).toFloat())
    }

    private fun maxDistance(color: IntArray, set: BooleanArray, to: Int): Float {
        var max = 0f
        for (i in set.indices) if (set[i]) max = maxOf(max, distance(color[i], to))
        return max
    }

    private fun overlap(a: Box, b: Box): Long =
        maxOf(0, minOf(a.right, b.right) - maxOf(a.left, b.left)).toLong() *
            maxOf(0, minOf(a.bottom, b.bottom) - maxOf(a.top, b.top))

    /** The middle value of each channel over the set pixels. */
    fun median(color: IntArray, set: BooleanArray): Int {
        val r = IntArray(LEVELS)
        val g = IntArray(LEVELS)
        val b = IntArray(LEVELS)
        var count = 0
        for (i in color.indices) {
            if (!set[i]) continue
            val c = color[i]
            r[(c shr 16) and 0xFF]++
            g[(c shr 8) and 0xFF]++
            b[c and 0xFF]++
            count++
        }
        fun mid(h: IntArray): Int {
            var sum = 0
            for (level in h.indices) {
                sum += h[level]
                if (sum * 2 >= count) return level
            }
            return LEVELS - 1
        }
        return (0xFF shl 24) or (mid(r) shl 16) or (mid(g) shl 8) or mid(b)
    }

    /**
     * The letters' pixels in two colour groups (a few rounds of 2-means, starting from the border's
     * and the inner pixels' middle colours): (fill colour, count) and (outline colour, count), the
     * outline being the group most of the border pixels fall in. `null` outline when all is one group.
     */
    private fun twoGroups(
        color: IntArray,
        mask: BooleanArray,
        border: BooleanArray,
        w: Int,
        h: Int,
    ): Pair<Pair<Int, Int>, Pair<Int, Int>?> {
        val inner = BooleanArray(mask.size) { mask[it] && !border[it] }
        if (inner.none { it }) return (median(color, mask) to mask.count { it }) to null
        var a = median(color, inner)
        var b = median(color, border)
        var inA = BooleanArray(mask.size)
        repeat(ROUNDS) {
            inA = BooleanArray(mask.size) { mask[it] && distance(color[it], a) <= distance(color[it], b) }
            val inB = BooleanArray(mask.size) { mask[it] && !inA[it] }
            if (inB.none { it } || inA.none { it }) return (median(color, mask) to mask.count { it }) to null
            a = median(color, inA)
            b = median(color, inB)
        }
        val countA = inA.count { it }
        val countB = mask.count { it } - countA
        // The outline is the group further out: nearer the background on average (a letter's
        // sharp edge touches the background with its fill, so which group touches it says little)
        val depth = distances(BooleanArray(mask.size) { !mask[it] }, w, h)
        var depthA = 0L
        var depthB = 0L
        for (i in mask.indices) if (mask[i]) if (inA[i]) depthA += depth[i] else depthB += depth[i]
        val outerA = depthA.toFloat() / countA.coerceAtLeast(1) < depthB.toFloat() / countB.coerceAtLeast(1)
        return if (outerA) (b to countB) to (a to countA) else (a to countA) to (b to countB)
    }

    /** Runs of rows with letters (small gaps bridged); the thin ones (a dot, a dash) are not lines. */
    private fun lineRuns(rows: IntArray): List<IntRange> {
        val peak = rows.maxOrNull() ?: 0
        if (peak == 0) return emptyList()
        val threshold = maxOf(1, (peak * LINE_SHARE).toInt())
        val runs = mutableListOf<IntRange>()
        var start = -1
        var last = -1
        for (y in rows.indices) {
            if (rows[y] >= threshold) {
                if (start < 0) start = y
                last = y
            } else if (start >= 0 && y - last > GAP) {
                runs += start..last
                start = -1
            }
        }
        if (start >= 0) runs += start..last
        val tallest = runs.maxOfOrNull { it.last - it.first + 1 } ?: return emptyList()
        return runs.filter { it.last - it.first + 1 >= tallest * MIN_LINE }
    }

    private const val MIN_BOX = 6
    private const val MIN_MARGIN = 4

    /** Letters are looked for this share of the box's shorter side around it... */
    private const val MARGIN = 0.3f

    /** ...then this many letter heights around it (at most this share of the box's longer side). */
    private const val REACH = 1.2f
    private const val REACH_ALONG = 6f
    private const val MAX_REACH = 0.5f

    /** Pieces at least this high (pixels) give the letters' size. */
    private const val MIN_PIECE = 3

    /** The text's pieces: those within this many letter sizes of the box... */
    private const val SEED_PAD = 0.25f

    /** ...and, one from another, at most this far apart along a line and across lines. */
    private const val CHAIN_ALONG = 1.5f
    private const val CHAIN_ACROSS = 0.6f

    /** Letters are at most this share of the model's box high. */
    private const val MAX_HEIGHT = 1.2f

    /** A piece of letters lying this much in another text's box, and this little in this one's, is the other's. */
    private const val THEIRS = 0.6f
    private const val MIN_OWN = 0.3f

    /** A glow is followed out at most twice this share of the letters' height from them (at least [MIN_HALO] pixels)... */
    private const val HALO = 0.3f
    private const val MIN_HALO = 2f

    /** ...and is this much lighter (towards the letters' colour) than the background further out. */
    private const val HALO_CONTRAST = 12f
    private const val CHAMFER = 3

    /** A background whose channels spread at most this much is one colour (noise of a scan or JPEG). */
    private const val FLAT = 10

    /** A background whose neighbouring pixels differ by at most this on average has no texture. */
    private const val SMOOTH = 3f

    /** Neighbours of one smooth area differ by at most this in each channel. */
    private const val STEP = 14

    /** The background covers at least this share of the area. */
    private const val MIN_BACKGROUND = 0.3f

    /** At least this many pixels of letters. */
    private const val MIN_INK = 20

    /** The letters lie at least this share inside the model's box (or the box inside them). */
    private const val MIN_ON_BOX = 0.3f

    /** Letters fill between these shares of the box around them: not specks, not a solid patch. */
    private const val MIN_SHARE = 0.04f
    private const val MAX_SHARE = 0.85f

    /** A plain area of the letters' colour this many squared letter heights large is no letter. */
    private const val BIG_PLAIN = 12f

    /** The background found inside the box covers at least this share of it. */
    private const val MIN_FILLING = 0.4f

    /** A piece is letters if this share of its pixels (or more) is nearer the letters' colour than the background's. */
    private const val MIN_INKY = 0.25f

    /** Colour distance (0–441) the letters stand out from the background by, at least. */
    private const val MIN_CONTRAST = 60f

    /** An outline differs from the fill at least this much, and each is this share of the letters. */
    private const val MIN_EDGE_CONTRAST = 70f
    private const val MIN_EDGE_SHARE = 0.15f

    /** An edge colour this close to the way from the fill to the background is a blur of the two... */
    private const val BETWEEN = 1.15f

    /** ...unless it is this many pixels thick: a glow. */
    private const val MIN_GLOW = 2f

    /** An outline running into the background is at least this many pixels thick. */
    private const val MIN_OUTLINE = 1f

    /** A glow fainter than this next to the letters is not drawn. */
    private const val MIN_GLOW_CONTRAST = 20f
    private const val ROUNDS = 4

    private const val LINE_SHARE = 0.15f
    private const val GAP = 1
    private const val MIN_LINE = 0.4f
    private const val LEVELS = 256
}
