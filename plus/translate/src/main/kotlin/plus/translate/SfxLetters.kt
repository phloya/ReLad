package plus.translate

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Finds a sound effect's letters drawn on the art: thick letters of one tone, each wrapped in an
 * edge of the other (dark letters with a white edge, white letters with a black one), as sound
 * effects are drawn. Gives what to erase (the letters and their edge) and how they look, so the
 * translation can be drawn the same way: letter height, slant, colours and edge.
 *
 * Works on plain ARGB pixels so it can be tested without Android.
 */
object SfxLetters {

    class Letters(
        /** The part of the page [mask] covers. */
        val area: Box,
        /** Pixels of [area], row by row, to erase: the letters with their edge. */
        val mask: BooleanArray,
        /** White letters with a dark edge; otherwise dark letters with a light one. */
        val light: Boolean,
        val fill: Int,
        val edge: Int,
        /** The edge's thickness, pixels. */
        val edgeWidth: Float,
        /** A letter's height across the line, pixels. */
        val letterHeight: Float,
        /** The longest line's length, pixels. */
        val length: Float,
        /** The lines' direction, degrees clockwise from the x axis. */
        val angle: Float,
        val centerX: Float,
        val centerY: Float,
        /** How many pieces of ink the letters are. */
        val pieces: Int,
        /** How thick the letters' strokes are, pixels. */
        val stroke: Float,
        /** The lines of letters, first to last: a sound may be drawn over two or three. */
        val lines: List<Line>,
    )

    /** Receives what was found and why, for tuning; `null` in the app. */
    internal var debug: ((String) -> Unit)? = null

    /** One line of a sound's letters: its middle on the page and its length. */
    class Line(val centerX: Float, val centerY: Float, val length: Float)

    /**
     * The letters of the sound effect [text] the model placed at [box]; `null` if no letters with
     * an edge are there (letters drawn straight onto a busy background can't be told from the art).
     */
    fun find(pixels: IntArray, width: Int, height: Int, box: Box, text: String): Letters? {
        val short = minOf(box.width, box.height)
        if (short < MIN_BOX) return null
        // Letters may stick out of the model's box; whatever reaches this far out is art
        val grown = box.grown((short * SEARCH).toInt() + 2, width, height)
        // Right next to the page's edge: up to it, so a letter cut by the edge is whole in the area
        val snap = (short * SNAP).toInt() + 2
        val area = Box(
            if (grown.left <= snap) 0 else grown.left,
            if (grown.top <= snap) 0 else grown.top,
            if (width - grown.right <= snap) width else grown.right,
            if (height - grown.bottom <= snap) height else grown.bottom,
        )
        val inner = box.grown((short * INSIDE).toInt(), width, height)
        val w = area.width
        val h = area.height
        val lum = IntArray(w * h) { BubbleEraser.luminance(pixels[(area.top + it / w) * width + area.left + it % w]) }
        val minThickness = maxOf(MIN_STROKE, short * MIN_STROKE_SHARE)
        val maxCount = (box.width.toLong() * box.height * MAX_PIECE_TO_BOX).toLong()
        // The page's own edges close a letter cut by them; the area's other sides don't
        val open = booleanArrayOf(area.left > 0, area.top > 0, area.right < width, area.bottom < height)
        val buffers = Buffers(w * h)
        val dark = pieces(lum, w, h, area, inner, open, darkFill = true, minThickness, maxCount, buffers)
        val light = pieces(lum, w, h, area, inner, open, darkFill = false, minThickness, maxCount, buffers)
        val expected = text.count { it.isLetterOrDigit() || it == '!' || it == '?' }.coerceAtLeast(1)
        debug?.invoke(
            "  sfx '$text' dark=${describe(dark)} light=${describe(light)}",
        )
        val lightFill = choose(dark.filter { it.inside }, light.filter { it.inside }, expected) ?: return null

        val candidates = if (lightFill) light else dark
        var chosen = candidates.filter { it.inside }
        // Specks next to the letters are art
        val biggest = chosen.maxOf { it.count }
        chosen = chosen.filter { it.count >= biggest * TINY }
        // Thin bits next to thick letters are art
        val typical = chosen.map { it.thickness }.sorted()[chosen.size / 2]
        chosen = chosen.filter { it.thickness >= typical * SAME_STROKE }
        if (chosen.size > expected * MAX_EXTRA + EXTRA_PIECES) return null

        val angle = lineAngle(chosen, w)
        val cos = cos(angle).toFloat()
        val sin = sin(angle).toFloat()
        fun u(x: Float, y: Float) = x * cos + y * sin
        fun v(x: Float, y: Float) = -x * sin + y * cos
        val across = HashMap<Piece, Float>()
        fun across(piece: Piece): Float = across.getOrPut(piece) {
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            for (i in piece.pixels) {
                val value = v((i % w).toFloat(), (i / w).toFloat())
                if (value < lo) lo = value
                if (value > hi) hi = value
            }
            hi - lo + 1
        }

        // The letters' usual height: dots and bits don't count, nor letters run together across lines
        fun letterHeight(pieces: List<Piece>): Float {
            val biggest = pieces.maxOf { it.count }
            val heights = pieces.filter { it.count >= biggest * LETTER_PIECE }.map { across(it) }.sorted()
            return heights[heights.size / 2]
        }
        var letterHeight = letterHeight(chosen)

        // The word's other letters, outside the model's box: next to the ones found, on their line
        repeat(GROW_STEPS) {
            val more = candidates.filter { piece ->
                piece !in chosen && piece.thickness >= typical * SAME_STROKE && piece.count >= biggest * TINY &&
                    across(piece) <= letterHeight * MAX_TALLER &&
                    chosen.any { c ->
                        abs(v(piece.cx, piece.cy) - v(c.cx, c.cy)) <= letterHeight * SAME_LINE &&
                            abs(u(piece.cx, piece.cy) - u(c.cx, c.cy)) <= letterHeight * NEXT_LETTER
                    }
            }
            chosen = chosen + more
        }

        // Lines: letters whose middles are close across the line direction
        val cjk = LetterCase.isCjk(text)
        val byLine = if (cjk) {
            listOf(chosen)
        } else {
            val sorted = chosen.sortedBy { v(it.cx, it.cy) }
            val groups = mutableListOf(mutableListOf(sorted[0]))
            for (k in 1 until sorted.size) {
                val gap = v(sorted[k].cx, sorted[k].cy) - v(sorted[k - 1].cx, sorted[k - 1].cy)
                if (gap > letterHeight * LINE_GAP) groups += mutableListOf<Piece>()
                groups.last() += sorted[k]
            }
            // A line of a bit or two much smaller than the others is art
            val most = groups.maxOf { g -> g.sumOf { it.count } }
            groups.filter { g -> g.sumOf { it.count } >= most * MIN_LINE_SHARE }
        }
        chosen = byLine.flatten()
        letterHeight = letterHeight(chosen)
        if (letterHeight < MIN_LETTER) return null
        val total = chosen.sumOf { it.count }
        if (total < box.width.toLong() * box.height * MIN_INK) return null

        val fill = BooleanArray(w * h)
        var vLo = Float.MAX_VALUE
        var vHi = -Float.MAX_VALUE
        var uLo = Float.MAX_VALUE
        var uHi = -Float.MAX_VALUE
        val lines = byLine.map { group ->
            var lineULo = Float.MAX_VALUE
            var lineUHi = -Float.MAX_VALUE
            var lineVLo = Float.MAX_VALUE
            var lineVHi = -Float.MAX_VALUE
            for (piece in group) {
                for (i in piece.pixels) {
                    fill[i] = true
                    val x = (i % w).toFloat()
                    val y = (i / w).toFloat()
                    val pu = u(x, y)
                    val pv = v(x, y)
                    if (pu < lineULo) lineULo = pu
                    if (pu > lineUHi) lineUHi = pu
                    if (pv < lineVLo) lineVLo = pv
                    if (pv > lineVHi) lineVHi = pv
                }
            }
            uLo = minOf(uLo, lineULo)
            uHi = maxOf(uHi, lineUHi)
            vLo = minOf(vLo, lineVLo)
            vHi = maxOf(vHi, lineVHi)
            floatArrayOf((lineULo + lineUHi) / 2, (lineVLo + lineVHi) / 2, lineUHi - lineULo + 1)
        }
        // Japanese and Chinese characters are many pieces each: the line is as high as a character
        val height = if (cjk) vHi - vLo + 1 else letterHeight

        // The edge: as far out as the other tone goes on around the letters
        val maxReach = (height * MAX_EDGE).toInt().coerceIn(MIN_EDGE_REACH, MAX_EDGE_PIXELS)
        val dist = distances(fill, w, h, maxReach + EXTRA_RING)
        val ring = IntArray(maxReach + EXTRA_RING + 1)
        val other = IntArray(ring.size)
        val same = IntArray(ring.size)
        for (i in dist.indices) {
            val d = dist[i]
            if (d <= 0) continue
            ring[d]++
            if (isEdge(lum[i], !lightFill)) other[d]++
            if (isFill(lum[i], !lightFill)) same[d]++
        }
        var edge = 0
        for (d in 1..maxReach) {
            if (ring[d] == 0) break
            // Right next to the letters the tones blend; further out an edge is solid, light art isn't
            val share = if (d == 1) (ring[d] - same[d]).toFloat() / ring[d] else other[d].toFloat() / ring[d]
            if (share < (if (d == 1) BLEND_SHARE else EDGE_SHARE)) break
            edge = d
        }
        // Nothing but the edge's tone around: letters on a plain page, no edge to speak of
        val plain = edge == maxReach
        var reach = edge
        if (!plain) {
            // A thin line of the letters' own tone around the edge, as on many Japanese sound effects
            for (d in edge + 1..minOf(edge + EXTRA_RING, ring.size - 1)) {
                if (ring[d] > 0 && same[d] >= ring[d] * BLEND_SHARE) reach = d
            }
        } else {
            reach = minOf(edge, maxOf(MIN_PLAIN_REACH, (height * PLAIN_EDGE).toInt()))
        }
        reach += if (height > BLUR_HEIGHT) 2 else 1
        val mask = BooleanArray(w * h) { dist[it] in 0..reach }

        val fillColor = medianColor(pixels, width, area, chosen.flatMap { it.pixels.asList() })
        val edgeColor = medianColor(
            pixels,
            width,
            area,
            dist.indices.filter { dist[it] in 1..maxOf(1, edge) && isEdge(lum[it], !lightFill) },
        )
        fun pageX(pu: Float, pv: Float) = area.left + pu * cos - pv * sin
        fun pageY(pu: Float, pv: Float) = area.top + pu * sin + pv * cos
        val uc = (uLo + uHi) / 2
        val vc = (vLo + vHi) / 2
        return Letters(
            area = area,
            mask = mask,
            light = lightFill,
            fill = fillColor ?: if (lightFill) WHITE else BLACK,
            edge = edgeColor ?: if (lightFill) BLACK else WHITE,
            edgeWidth = minOf(edge.toFloat(), height * if (plain) PLAIN_EDGE else MAX_DRAWN_EDGE),
            letterHeight = height,
            length = lines.maxOf { it[2] },
            angle = Math.toDegrees(angle).toFloat(),
            centerX = pageX(uc, vc),
            centerY = pageY(uc, vc),
            pieces = chosen.size,
            stroke = chosen.map { it.thickness }.sorted()[chosen.size / 2],
            lines = lines.map { Line(pageX(it[0], it[1]), pageY(it[0], it[1]), it[2]) },
        )
    }

    /** Work arrays for one area, shared by both tones' search. */
    private class Buffers(size: Int) {
        val labelled = BooleanArray(size)
        val queue = IntArray(size)
        val flood = IntArray(size)
        val floodQueue = IntArray(size)
        var stamp = 0
    }

    private class Piece(
        val pixels: IntArray,
        val thickness: Float,
        val cx: Float,
        val cy: Float,
        /** Mostly inside the model's box. */
        val inside: Boolean,
        /** The middle of the piece's extent (a letter's mass may sit high, as a Г's). */
        val mx: Float,
        val my: Float,
        /** How tall the piece is on the page. */
        val tall: Int,
    ) {
        val count: Int get() = pixels.size
    }

    /** Letter tone: dark letters are darker than this, light ones lighter. */
    private fun isFill(lum: Int, darkFill: Boolean) = if (darkFill) lum < DARK else lum > LIGHT

    /** The edge's tone around such letters. */
    private fun isEdge(lum: Int, darkFill: Boolean) = if (darkFill) lum > EDGE_LIGHT else lum < EDGE_DARK

    /**
     * Pieces of the [darkFill] tone that could be letters: thick, not reaching the area's [open]
     * sides (left, top, right, bottom), and wrapped all round in the other tone.
     */
    private fun pieces(
        lum: IntArray,
        w: Int,
        h: Int,
        area: Box,
        inner: Box,
        open: BooleanArray,
        darkFill: Boolean,
        minThickness: Float,
        maxCount: Long,
        buffers: Buffers,
    ): List<Piece> {
        val n = w * h
        val labelled = buffers.labelled.also { it.fill(false) }
        val queue = buffers.queue
        val flood = buffers.flood
        val floodQueue = buffers.floodQueue
        val result = ArrayList<Piece>()
        for (start in 0 until n) {
            if (labelled[start] || !isFill(lum[start], darkFill)) continue
            labelled[start] = true
            var head = 0
            var tail = 0
            queue[tail++] = start
            var touches = false
            var inside = 0
            var perimeter = 0
            var sx = 0L
            var sy = 0L
            var minX = w
            var minY = h
            var maxX = -1
            var maxY = -1
            while (head < tail) {
                val i = queue[head++]
                val x = i % w
                val y = i / w
                if (reachesOpen(x, y, w, h, open)) touches = true
                if (area.left + x in inner.left until inner.right && area.top + y in inner.top until inner.bottom) {
                    inside++
                }
                sx += x
                sy += y
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                var boundary = false
                for (k in 0 until 4) {
                    val nx = x + DX[k]
                    val ny = y + DY[k]
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) {
                        boundary = true
                        continue
                    }
                    val j = ny * w + nx
                    if (isFill(lum[j], darkFill)) {
                        if (!labelled[j]) {
                            labelled[j] = true
                            queue[tail++] = j
                        }
                    } else {
                        boundary = true
                    }
                }
                if (boundary) perimeter++
            }
            if (touches || tail < MIN_PIXELS || tail > maxCount) continue
            // A stroke of width t and length l has about 2l pixels on its border
            val thickness = 2f * tail / perimeter.coerceAtLeast(1)
            if (thickness < minThickness) continue
            val own = queue.copyOf(tail)
            buffers.stamp++
            if (!enclosed(own, perimeter, lum, w, h, open, darkFill, flood, floodQueue, buffers.stamp)) continue
            result += Piece(
                own,
                thickness,
                sx.toFloat() / tail,
                sy.toFloat() / tail,
                inside * 2 >= tail,
                (minX + maxX) / 2f,
                (minY + maxY) / 2f,
                maxY - minY + 1,
            )
        }
        return result
    }

    /** No way out of the piece but through the other tone: it has an edge all round. */
    private fun enclosed(
        own: IntArray,
        perimeter: Int,
        lum: IntArray,
        w: Int,
        h: Int,
        open: BooleanArray,
        darkFill: Boolean,
        flood: IntArray,
        queue: IntArray,
        stamp: Int,
    ): Boolean {
        // The piece and its blended border; a way out runs much further
        val limit = (own.size + perimeter * ENCLOSE_RING) * ENCLOSE_SLACK
        var tail = 0
        for (i in own) {
            flood[i] = stamp
            queue[tail++] = i
        }
        var head = 0
        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            val y = i / w
            if (reachesOpen(x, y, w, h, open)) return false
            for (k in 0 until 4) {
                val nx = x + DX[k]
                val ny = y + DY[k]
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                val j = ny * w + nx
                if (flood[j] == stamp || isEdge(lum[j], darkFill)) continue
                flood[j] = stamp
                queue[tail++] = j
                if (tail > limit) return false
            }
        }
        return true
    }

    private fun describe(pieces: List<Piece>) = pieces.joinToString(prefix = "[", postfix = "]") {
        "${it.count}/${"%.1f".format(it.thickness)}${if (it.inside) "" else "o"}@${it.cx.toInt()},${it.cy.toInt()}"
    }

    /** On a side of the area that is not the page's edge. */
    private fun reachesOpen(x: Int, y: Int, w: Int, h: Int, open: BooleanArray) =
        (x == 0 && open[0]) || (y == 0 && open[1]) || (x == w - 1 && open[2]) || (y == h - 1 && open[3])

    /**
     * Which tone the letters are. Not the one with far less ink (the holes in letters); else the
     * one much fatter (letters are fatter than their edge); else the one with about as many pieces
     * as the text has letters (the edge around a word is one piece, letters' holes are few); else
     * the one with more ink.
     */
    private fun choose(dark: List<Piece>, light: List<Piece>, expected: Int): Boolean? {
        if (dark.isEmpty() && light.isEmpty()) return null
        if (dark.isEmpty()) return true
        if (light.isEmpty()) return false
        // Holes in letters have much less ink than the letters; letters and their edge, about as much
        val lightInk = light.sumOf { it.count }.toFloat()
        val darkInk = dark.sumOf { it.count }.toFloat()
        if (lightInk < darkInk * LOPSIDED) return false
        if (darkInk < lightInk * LOPSIDED) return true
        val fatter =
            light.map { it.thickness }.sorted()[light.size / 2] / dark.map { it.thickness }.sorted()[dark.size / 2]
        if (fatter > FATTER) return true
        if (fatter < 1 / FATTER) return false
        val offDark = abs(ln(dark.size.toFloat() / expected))
        val offLight = abs(ln(light.size.toFloat() / expected))
        if (abs(offDark - offLight) > CLOSER) return offLight < offDark
        return light.sumOf { it.count } > dark.sumOf { it.count }
    }

    /**
     * The direction the letters' lines run, radians. Roughly: from each letter to its nearest
     * neighbour, which is on its own line, so two or three lines one under another give their own
     * direction, not the one across them. Then exactly: the turn near that at which the letters'
     * ink, looked at across the line, packs into the fullest rows (as a scan is straightened).
     * Level for a single letter, or when level is about as good.
     */
    private fun lineAngle(pieces: List<Piece>, w: Int): Double {
        val biggest = pieces.maxOf { it.count }
        val big = pieces.filter { it.count >= biggest * LETTER_PIECE }
        // Letters run together across two lines would pull the direction across them
        val usual = big.map { it.tall }.sorted()[big.size / 2]
        val letters = big.filter { it.tall <= usual * MAX_TALLER }
        if (letters.size < 2) return 0.0
        var c = 0.0
        var s = 0.0
        var weight = 0.0
        for (a in letters) {
            val b = letters.filter { it !== a }.minBy {
                (it.mx - a.mx) * (it.mx - a.mx) +
                    (it.my - a.my) * (it.my - a.my)
            }
            // Doubled: a direction and its opposite are the same line
            val theta = 2 * atan2((b.my - a.my).toDouble(), (b.mx - a.mx).toDouble())
            c += cos(theta) * a.count
            s += sin(theta) * a.count
            weight += a.count
        }
        if (sqrt(c * c + s * s) < weight * MIN_AGREEMENT) return 0.0
        val rough = 0.5 * atan2(s, c)

        val all = letters.sumOf { it.count }
        val step = maxOf(1, all / ANGLE_SAMPLES)
        val xs = ArrayList<Float>(all / step + letters.size)
        val ys = ArrayList<Float>(all / step + letters.size)
        for (piece in letters) {
            for (k in piece.pixels.indices step step) {
                xs += (piece.pixels[k] % w).toFloat()
                ys += (piece.pixels[k] / w).toFloat()
            }
        }
        fun packed(theta: Double): Double {
            val sin = sin(theta).toFloat()
            val cos = cos(theta).toFloat()
            var lo = Float.MAX_VALUE
            for (k in xs.indices) lo = minOf(lo, -xs[k] * sin + ys[k] * cos)
            val rows = HashMap<Int, Int>()
            for (k in xs.indices) {
                val row = (-xs[k] * sin + ys[k] * cos - lo).toInt()
                rows[row] = (rows[row] ?: 0) + 1
            }
            return rows.values.sumOf { it.toDouble() * it }
        }
        var best = rough
        var bestScore = packed(rough)
        for (k in -REFINE_STEPS..REFINE_STEPS) {
            val theta = rough + Math.toRadians(k * REFINE_TILT)
            val score = packed(theta)
            if (score > bestScore) {
                bestScore = score
                best = theta
            }
        }
        val levelScore = if (abs(rough) < Math.toRadians(REFINE_STEPS * REFINE_TILT)) packed(0.0) else 0.0
        return if (abs(best) < LEVEL || bestScore < levelScore * LEVEL_GAIN) 0.0 else best
    }

    /** Distance (in a square's steps) from the nearest set pixel, up to [max]; -1 further. */
    private fun distances(set: BooleanArray, w: Int, h: Int, max: Int): IntArray {
        val dist = IntArray(set.size) { if (set[it]) 0 else -1 }
        val queue = IntArray(set.size)
        var tail = 0
        for (i in set.indices) if (set[i]) queue[tail++] = i
        var head = 0
        while (head < tail) {
            val i = queue[head++]
            val d = dist[i]
            if (d >= max) continue
            val x = i % w
            val y = i / w
            for (dy in -1..1) {
                val ny = y + dy
                if (ny < 0 || ny >= h) continue
                for (dx in -1..1) {
                    val nx = x + dx
                    if (nx < 0 || nx >= w) continue
                    val j = ny * w + nx
                    if (dist[j] >= 0) continue
                    dist[j] = d + 1
                    queue[tail++] = j
                }
            }
        }
        return dist
    }

    /** The colour of middle lightness among [indices] (area pixels); `null` if none. */
    private fun medianColor(pixels: IntArray, width: Int, area: Box, indices: List<Int>): Int? {
        if (indices.isEmpty()) return null
        val step = maxOf(1, indices.size / COLOR_SAMPLES)
        val colors = (indices.indices step step).map {
            val i = indices[it]
            pixels[(area.top + i / area.width) * width + area.left + i % area.width]
        }
        return colors.sortedBy { BubbleEraser.luminance(it) }[colors.size / 2] or OPAQUE
    }

    private val DX = intArrayOf(1, -1, 0, 0)
    private val DY = intArrayOf(0, 0, 1, -1)

    private const val MIN_BOX = 12

    /** Letters are looked for this far around the model's box (share of its shorter side)... */
    private const val SEARCH = 0.8f

    /** ...and at least half of each is this close to it, or it is next to such letters on their line. */
    private const val INSIDE = 0.12f
    private const val GROW_STEPS = 3
    private const val SAME_LINE = 0.6f
    private const val NEXT_LETTER = 1.8f
    private const val MAX_TALLER = 2.2f

    /** Letters whose middles are this many letter heights apart across the line are on two lines. */
    private const val LINE_GAP = 0.75f

    /** A line with less ink than this share of the fullest one is art. */
    private const val MIN_LINE_SHARE = 0.15f

    /** Smaller letters than this (pixels) are left alone: too small to tell from the art. */
    private const val MIN_LETTER = 12f

    /** One tone's pieces this much thicker than the other's are the letters. */
    private const val FATTER = 1.4f

    private const val DARK = 90
    private const val LIGHT = 185
    private const val EDGE_LIGHT = 170
    private const val EDGE_DARK = 100

    /** A letter's stroke is at least this thick: pixels, share of the box's shorter side. */
    private const val MIN_STROKE = 3f
    private const val MIN_STROKE_SHARE = 0.04f
    private const val MIN_PIXELS = 12
    private const val MAX_PIECE_TO_BOX = 1.2f

    /** A piece with a way out through this many blended border rings (twice over) is art. */
    private const val ENCLOSE_RING = 3
    private const val ENCLOSE_SLACK = 2

    /** How much closer to the text's letter count one tone's pieces must be to win outright. */
    private const val CLOSER = 0.4f

    /** Pieces thinner than this share of the typical letter's stroke are art. */
    private const val SAME_STROKE = 0.4f

    /** More pieces than this many per letter (and a few) is art, not letters. */
    private const val MAX_EXTRA = 3
    private const val EXTRA_PIECES = 6

    /** Letters' ink takes at least this share of the box. */
    private const val MIN_INK = 0.03f

    /** Pieces smaller than this share of the biggest are specks of art... */
    private const val TINY = 0.03f

    /** ...and smaller than this share, dots and bits that don't set the line's direction. */
    private const val LETTER_PIECE = 0.15f

    /** One tone with less than this share of the other's ink is the holes in its letters. */
    private const val LOPSIDED = 0.35f

    /** The search area goes up to the page's edge when this near it (share of the box's shorter side). */
    private const val SNAP = 0.3f

    /** How much letters' directions to their neighbours agree (1: all the same) for a tilted line. */
    private const val MIN_AGREEMENT = 0.5

    /** Tilts tried around the rough one, degrees. */
    private const val REFINE_STEPS = 24
    private const val REFINE_TILT = 0.5
    private const val ANGLE_SAMPLES = 30000

    /** A tilt must pack the letters' rows this much better than level to be kept. */
    private const val LEVEL_GAIN = 1.05

    /** Lines tilted less than this (radians, 4°) are set level. */
    private const val LEVEL = 0.07

    /** The edge is looked for up to this share of the letter height out. */
    private const val MAX_EDGE = 0.35f
    private const val MIN_EDGE_REACH = 3
    private const val MAX_EDGE_PIXELS = 48

    /** Rings looked at past the edge for a thin outer line. */
    private const val EXTRA_RING = 3

    /** A ring is still the edge while this share of it is the edge's tone (right next to the letters, not theirs). */
    private const val EDGE_SHARE = 0.75f
    private const val BLEND_SHARE = 0.5f

    /** A redrawn edge is at most this share of the letter height thick (art of the edge's tone around adds to it). */
    private const val MAX_DRAWN_EDGE = 0.18f

    /** On a plain page only this much around the letters is erased (share of their height, at least pixels). */
    private const val PLAIN_EDGE = 0.08f
    private const val MIN_PLAIN_REACH = 2

    /** Letters taller than this (pixels) have a wider blended border. */
    private const val BLUR_HEIGHT = 80

    private const val COLOR_SAMPLES = 2000

    private const val OPAQUE = -0x1000000
    private const val WHITE = -0x1
    private const val BLACK = -0x1000000
}
