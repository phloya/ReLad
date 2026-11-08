package plus.translate

/**
 * Fills a hole in a smooth background (a gradient, a glow, a softly lit screen) from the colours
 * around it: averaged over ever coarser grids where the hole is, then drawn back down, so each
 * hole pixel gets the colour the background around would have there. Much faster than [Inpaint],
 * which copies texture; a smooth background has none to copy.
 *
 * Works on plain ARGB pixels so it can be tested without Android.
 */
object SmoothFill {

    /**
     * Fills the pixels of [hole] (row by row inside [area]) in [pixels]. Only pixels set in [known]
     * (the same grid) are taken as the background's colour; others (art at the edge) are left out.
     */
    fun fill(pixels: IntArray, width: Int, area: Box, hole: BooleanArray, known: BooleanArray) {
        val w = area.width
        val h = area.height
        if (w <= 0 || h <= 0 || hole.none { it }) return
        val levels = mutableListOf<Level>()
        var level = Level(w, h).apply {
            for (i in 0 until w * h) {
                if (!known[i] || hole[i]) continue
                val c = pixels[(area.top + i / w) * width + area.left + i % w]
                r[i] = ((c shr 16) and 0xFF).toFloat()
                g[i] = ((c shr 8) and 0xFF).toFloat()
                b[i] = (c and 0xFF).toFloat()
                weight[i] = 1f
            }
        }
        levels += level
        // Push: each coarser cell is its known children's average
        while (level.w > 1 || level.h > 1) {
            val coarse = Level((level.w + 1) / 2, (level.h + 1) / 2)
            for (y in 0 until level.h) {
                for (x in 0 until level.w) {
                    val i = y * level.w + x
                    val k = level.weight[i]
                    if (k == 0f) continue
                    val j = (y / 2) * coarse.w + x / 2
                    coarse.r[j] += level.r[i] * k
                    coarse.g[j] += level.g[i] * k
                    coarse.b[j] += level.b[i] * k
                    coarse.weight[j] += k
                }
            }
            for (j in 0 until coarse.w * coarse.h) {
                val k = coarse.weight[j]
                if (k == 0f) continue
                coarse.r[j] /= k
                coarse.g[j] /= k
                coarse.b[j] /= k
                coarse.weight[j] = minOf(1f, k)
            }
            levels += coarse
            level = coarse
        }
        // Pull: where a cell knows too little, its colour comes from the coarser grid (bilinear)
        for (l in levels.size - 2 downTo 0) {
            val fine = levels[l]
            val coarse = levels[l + 1]
            for (y in 0 until fine.h) {
                for (x in 0 until fine.w) {
                    val i = y * fine.w + x
                    val k = fine.weight[i]
                    if (k >= 1f) continue
                    val cx = ((x - 0.5f) / 2f).coerceIn(0f, (coarse.w - 1).toFloat())
                    val cy = ((y - 0.5f) / 2f).coerceIn(0f, (coarse.h - 1).toFloat())
                    val x0 = cx.toInt()
                    val y0 = cy.toInt()
                    val x1 = minOf(x0 + 1, coarse.w - 1)
                    val y1 = minOf(y0 + 1, coarse.h - 1)
                    val fx = cx - x0
                    val fy = cy - y0
                    fun at(values: FloatArray): Float {
                        val top = values[y0 * coarse.w + x0] * (1 - fx) + values[y0 * coarse.w + x1] * fx
                        val bottom = values[y1 * coarse.w + x0] * (1 - fx) + values[y1 * coarse.w + x1] * fx
                        return top * (1 - fy) + bottom * fy
                    }
                    fine.r[i] = fine.r[i] * k + at(coarse.r) * (1 - k)
                    fine.g[i] = fine.g[i] * k + at(coarse.g) * (1 - k)
                    fine.b[i] = fine.b[i] * k + at(coarse.b) * (1 - k)
                    fine.weight[i] = 1f
                }
            }
        }
        val top = levels[0]
        // A few rounds of averaging inside the hole smooth away the grids' blocks
        repeat(SMOOTHING) {
            for (i in 0 until w * h) {
                if (!hole[i]) continue
                val x = i % w
                val y = i / w
                var r = 0f
                var g = 0f
                var b = 0f
                var count = 0
                for (j in intArrayOf(i - 1, i + 1, i - w, i + w)) {
                    if (j < 0 || j >= w * h || (j == i - 1 && x == 0) || (j == i + 1 && x == w - 1)) continue
                    r += top.r[j]
                    g += top.g[j]
                    b += top.b[j]
                    count++
                }
                if (count == 0) continue
                top.r[i] = r / count
                top.g[i] = g / count
                top.b[i] = b / count
            }
        }
        for (i in 0 until w * h) {
            if (!hole[i]) continue
            val p = (area.top + i / w) * width + area.left + i % w
            pixels[p] = (pixels[p] and -0x1000000) or
                (channel(top.r[i]) shl 16) or (channel(top.g[i]) shl 8) or channel(top.b[i])
        }
    }

    private fun channel(value: Float) = (value + 0.5f).toInt().coerceIn(0, 255)

    private class Level(val w: Int, val h: Int) {
        val r = FloatArray(w * h)
        val g = FloatArray(w * h)
        val b = FloatArray(w * h)
        val weight = FloatArray(w * h)
    }

    private const val SMOOTHING = 8
}
