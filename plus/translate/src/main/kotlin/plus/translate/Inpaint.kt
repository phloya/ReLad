package plus.translate

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Fills a hole in the art from the art around it, patch by patch (exemplar-based inpainting, after
 * Criminisi, Pérez and Toyama, 2004): from the hole's edge inwards, first where the art's lines run
 * into it, each time copying the nearby piece of art that best matches what is already known
 * around that spot. Lines, hatching and screentone go on into the hole instead of blurring to gray.
 *
 * Works on plain ARGB pixels so it can be tested without Android.
 */
object Inpaint {

    /**
     * Fills the pixels of [hole] (row by row inside [area]) in [pixels]. Pixels set in [blocked]
     * (page-sized: letters still to be erased) are never copied.
     */
    fun fill(pixels: IntArray, width: Int, height: Int, area: Box, hole: BooleanArray, blocked: BooleanArray? = null) {
        val aw = area.width
        val ah = area.height
        var x0 = Int.MAX_VALUE
        var y0 = Int.MAX_VALUE
        var x1 = -1
        var y1 = -1
        for (i in hole.indices) {
            if (!hole[i]) continue
            val x = area.left + i % aw
            val y = area.top + i / aw
            if (x < x0) x0 = x
            if (y < y0) y0 = y
            if (x > x1) x1 = x
            if (y > y1) y1 = y
        }
        if (x1 < 0) return
        val p = (width / PAGE_PER_PATCH).roundToInt().coerceIn(MIN_PATCH, MAX_PATCH)
        // Patches are looked for about as far away as the hole is thick
        val reach = (halfThickness(hole, aw, ah) * 2 + p * 2).coerceIn(p * MIN_REACH, MAX_REACH)
        // Patches are compared over a little more than what is copied: where the art around goes
        val m = p + CONTEXT
        val window = Box(x0, y0, x1 + 1, y1 + 1).grown(reach + m, width, height)
        val ww = window.width
        val wh = window.height
        val n = ww * wh
        val color = IntArray(n)
        val lum = IntArray(n)
        val unknown = BooleanArray(n)
        var left = 0
        for (y in 0 until wh) {
            for (x in 0 until ww) {
                val i = y * ww + x
                val c = pixels[(window.top + y) * width + window.left + x]
                color[i] = c
                lum[i] = BubbleEraser.luminance(c)
                val ax = window.left + x - area.left
                val ay = window.top + y - area.top
                if (ax in 0 until aw && ay in 0 until ah && hole[ay * aw + ax]) {
                    unknown[i] = true
                    left++
                }
            }
        }

        // Patches to copy from: wholly known from the start and not blocked (prefix sums of the rest)
        val stride = ww + 1
        val bad = IntArray(stride * (wh + 1))
        for (y in 0 until wh) {
            var row = 0
            for (x in 0 until ww) {
                val i = y * ww + x
                if (unknown[i] || blocked?.get((window.top + y) * width + window.left + x) == true) row++
                bad[(y + 1) * stride + x + 1] = bad[y * stride + x + 1] + row
            }
        }
        fun isSource(cx: Int, cy: Int): Boolean {
            if (cx < m || cy < m || cx >= ww - m || cy >= wh - m) return false
            val a = (cy - m) * stride + cx - m
            val b = (cy + m + 1) * stride + cx - m
            val size = 2 * m + 1
            return bad[b + size] - bad[b] - bad[a + size] + bad[a] == 0
        }

        val confidence = FloatArray(n) { if (unknown[it]) 0f else 1f }
        val gx = FloatArray(n)
        val gy = FloatArray(n)
        fun gradient(i: Int) {
            val x = i % ww
            val y = i / ww
            if (x < 1 || y < 1 || x >= ww - 1 || y >= wh - 1 || unknown[i] ||
                unknown[i - 1] || unknown[i + 1] || unknown[i - ww] || unknown[i + ww]
            ) {
                gx[i] = 0f
                gy[i] = 0f
            } else {
                gx[i] = (lum[i + 1] - lum[i - 1]) / 2f
                gy[i] = (lum[i + ww] - lum[i - ww]) / 2f
            }
        }
        for (i in 0 until n) gradient(i)

        fun isFront(i: Int): Boolean {
            if (!unknown[i]) return false
            val x = i % ww
            val y = i / ww
            return (x > 0 && !unknown[i - 1]) || (x < ww - 1 && !unknown[i + 1]) ||
                (y > 0 && !unknown[i - ww]) || (y < wh - 1 && !unknown[i + ww])
        }
        val inFront = BooleanArray(n)
        var front = IntArray(INITIAL_FRONT)
        var frontSize = 0
        fun addFront(i: Int) {
            if (inFront[i]) return
            inFront[i] = true
            if (frontSize == front.size) front = front.copyOf(front.size * 2)
            front[frontSize++] = i
        }
        for (i in 0 until n) if (isFront(i)) addFront(i)

        fun known(x: Int, y: Int) = x in 0 until ww && y in 0 until wh && !unknown[y * ww + x]

        // Fill first where much is known around and a line runs into the hole. A line is where the
        // gradients around keep one direction; hatching and screentone go every way, and are filled
        // in their turn from the edge inwards
        fun priority(i: Int): Float {
            val tx = i % ww
            val ty = i / ww
            var sum = 0f
            var jxx = 0f
            var jxy = 0f
            var jyy = 0f
            var counted = 0
            for (y in maxOf(0, ty - p)..minOf(wh - 1, ty + p)) {
                for (x in maxOf(0, tx - p)..minOf(ww - 1, tx + p)) {
                    val j = y * ww + x
                    sum += confidence[j]
                    if (unknown[j]) continue
                    jxx += gx[j] * gx[j]
                    jxy += gx[j] * gy[j]
                    jyy += gy[j] * gy[j]
                    counted++
                }
            }
            val half = (jxx + jyy) / 2
            val spread = sqrt(maxOf(0f, half * half - (jxx * jyy - jxy * jxy)))
            val coherence = if (half > 0f) spread / half else 0f
            val strength = if (counted > 0) minOf(1f, sqrt((half + spread) / counted) / STRONG_LINE) else 0f
            // Across the gradient: along the line
            val phi = 0.5f * kotlin.math.atan2(2 * jxy, jxx - jyy)
            val isoX = -kotlin.math.sin(phi)
            val isoY = kotlin.math.cos(phi)
            // The hole's edge direction: where the known pixels are
            var nx = 0f
            var ny = 0f
            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (!known(tx + dx, ty + dy)) continue
                    nx -= dx
                    ny -= dy
                }
            }
            val norm = sqrt(nx * nx + ny * ny)
            val data = if (norm > 0f) coherence * coherence * strength * abs(isoX * nx + isoY * ny) / norm else 0f
            return sum / ((2 * p + 1) * (2 * p + 1)) * (data + DATA_FLOOR)
        }
        val priorities = FloatArray(n)
        val dirty = BooleanArray(n) { true }

        val offsets = IntArray((2 * m + 1) * (2 * m + 1))
        val values = IntArray(offsets.size)
        while (left > 0) {
            var best = -1
            var bestPriority = -1f
            var k = 0
            while (k < frontSize) {
                val i = front[k]
                if (!isFront(i)) {
                    inFront[i] = false
                    front[k] = front[--frontSize]
                    continue
                }
                if (dirty[i]) {
                    priorities[i] = priority(i)
                    dirty[i] = false
                }
                if (priorities[i] > bestPriority) {
                    bestPriority = priorities[i]
                    best = i
                }
                k++
            }
            if (best < 0) break
            val tx = best % ww
            val ty = best / ww

            // What is known around the spot...
            var count = 0
            for (dy in -m..m) {
                for (dx in -m..m) {
                    if (!known(tx + dx, ty + dy)) continue
                    offsets[count] = dy * ww + dx
                    values[count] = lum[(ty + dy) * ww + tx + dx]
                    count++
                }
            }
            // ...and the nearby patch that matches it best (the nearer the better: the art changes
            // across the page): every other place first, then next to the best
            var source = -1
            var bestCost = Long.MAX_VALUE
            val perStep = (count * DISTANCE_COST).toLong()
            fun tryAt(cx: Int, cy: Int) {
                if (!isSource(cx, cy)) return
                val center = cy * ww + cx
                var cost = perStep * ((cx - tx) * (cx - tx) + (cy - ty) * (cy - ty))
                if (cost >= bestCost) return
                for (k2 in 0 until count) {
                    val d = lum[center + offsets[k2]] - values[k2]
                    cost += d * d
                    if (cost >= bestCost) return
                }
                bestCost = cost
                source = center
            }
            for (cy in ty - reach..ty + reach step COARSE) {
                for (cx in tx - reach..tx + reach step COARSE) tryAt(cx, cy)
            }
            if (source >= 0) {
                val sx = source % ww
                val sy = source / ww
                for (cy in sy - 1..sy + 1) for (cx in sx - 1..sx + 1) tryAt(cx, cy)
            }

            // Copied pixels are as sure as what was known around them
            val trust = sumConfidence(confidence, tx, ty, p, ww, wh)
            for (dy in -p..p) {
                val y = ty + dy
                if (y < 0 || y >= wh) continue
                for (dx in -p..p) {
                    val x = tx + dx
                    if (x < 0 || x >= ww) continue
                    val i = y * ww + x
                    if (!unknown[i]) continue
                    if (source >= 0) {
                        val s = source + dy * ww + dx
                        color[i] = color[s]
                        lum[i] = lum[s]
                    } else {
                        color[i] = average(color, unknown, x, y, ww, wh)
                        lum[i] = BubbleEraser.luminance(color[i])
                    }
                    unknown[i] = false
                    confidence[i] = trust
                    left--
                }
            }
            // The spot's surroundings changed: their gradients, edge and priorities
            for (y in maxOf(0, ty - p - 1)..minOf(wh - 1, ty + p + 1)) {
                for (x in maxOf(0, tx - p - 1)..minOf(ww - 1, tx + p + 1)) {
                    val i = y * ww + x
                    gradient(i)
                    if (isFront(i)) addFront(i)
                }
            }
            val r = 2 * p + 1
            for (y in maxOf(0, ty - r)..minOf(wh - 1, ty + r)) {
                for (x in maxOf(0, tx - r)..minOf(ww - 1, tx + r)) dirty[y * ww + x] = true
            }
        }

        for (y in 0 until wh) {
            for (x in 0 until ww) {
                val ax = window.left + x - area.left
                val ay = window.top + y - area.top
                if (ax in 0 until aw && ay in 0 until ah && hole[ay * aw + ax]) {
                    pixels[(window.top + y) * width + window.left + x] = color[y * ww + x]
                }
            }
        }
    }

    /** The mean confidence of the patch around (tx, ty). */
    private fun sumConfidence(confidence: FloatArray, tx: Int, ty: Int, p: Int, ww: Int, wh: Int): Float {
        var sum = 0f
        for (y in maxOf(0, ty - p)..minOf(wh - 1, ty + p)) {
            for (x in maxOf(0, tx - p)..minOf(ww - 1, tx + p)) sum += confidence[y * ww + x]
        }
        return sum / ((2 * p + 1) * (2 * p + 1))
    }

    /** The mean colour of the known pixels next to (x, y); white if none. */
    private fun average(color: IntArray, unknown: BooleanArray, x: Int, y: Int, ww: Int, wh: Int): Int {
        var r = 0
        var g = 0
        var b = 0
        var count = 0
        for (ny in maxOf(0, y - 1)..minOf(wh - 1, y + 1)) {
            for (nx in maxOf(0, x - 1)..minOf(ww - 1, x + 1)) {
                val i = ny * ww + nx
                if (unknown[i]) continue
                r += (color[i] shr 16) and 0xFF
                g += (color[i] shr 8) and 0xFF
                b += color[i] and 0xFF
                count++
            }
        }
        if (count == 0) return -0x1
        return (0xFF shl 24) or ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
    }

    /** How far the hole's middle is from its edge, in pixels. */
    private fun halfThickness(hole: BooleanArray, w: Int, h: Int): Int {
        val dist = IntArray(hole.size) { -1 }
        val queue = IntArray(hole.size)
        var tail = 0
        for (i in hole.indices) {
            if (!hole[i]) continue
            val x = i % w
            val y = i / w
            val edge = x == 0 || y == 0 || x == w - 1 || y == h - 1 ||
                !hole[i - 1] || !hole[i + 1] || !hole[i - w] || !hole[i + w]
            if (edge) {
                dist[i] = 1
                queue[tail++] = i
            }
        }
        var head = 0
        var max = 0
        while (head < tail) {
            val i = queue[head++]
            val d = dist[i]
            if (d > max) max = d
            val x = i % w
            val y = i / w
            for (j in intArrayOf(i - 1, i + 1, i - w, i + w)) {
                if (j < 0 || j >= hole.size || !hole[j] || dist[j] >= 0) continue
                if ((j == i - 1 && x == 0) || (j == i + 1 && x == w - 1)) continue
                if ((j == i - w && y == 0) || (j == i + w && y == h - 1)) continue
                dist[j] = d + 1
                queue[tail++] = j
            }
        }
        return max
    }

    /** The patch's half size: one pixel per this many pixels of page width... */
    private const val PAGE_PER_PATCH = 260f
    private const val MIN_PATCH = 3
    private const val MAX_PATCH = 6

    /** ...and patches are looked for at least this many patch sizes away, at most this many pixels. */
    private const val MIN_REACH = 6
    private const val MAX_REACH = 80

    /** Every other place is tried first. */
    private const val COARSE = 2

    /** Pixels compared around what is copied. */
    private const val CONTEXT = 2

    /** The cost of a patch a pixel further away, per pixel compared (as a squared lightness difference). */
    private const val DISTANCE_COST = 0.25f

    /** A gradient this strong (lightness steps per pixel) is a full line. */
    private const val STRONG_LINE = 30f

    /** A spot with no line running into it is still filled in its turn. */
    private const val DATA_FLOOR = 0.15f
    private const val INITIAL_FRONT = 1024
}
