package plus.enhance

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Finds screentone the network cannot resolve and smooths it before upscaling.
 *
 * Real-CUGAN sharpens what it sees. Screentone whose dots are 2–4 px apart and softened by the scan
 * is too fine for it: it turns such tone into a mottled "worm" texture. That tone is found by four
 * local statistics (on a half-size grid, windows of ~10 px):
 * - energy of the pixel-level checker pattern (fine dots in both directions; a horizontal or
 *   vertical edge has none of it);
 * - its share of all fine detail (dots rather than edges and corners);
 * - gradient coherence (hatching and speed lines run one way, dots do not);
 * - gradient strength (only low-contrast texture: crisp tone and ink strokes come out fine).
 *
 * Found tone is smoothed with a bilateral filter, which flattens low-contrast texture and keeps
 * lines and text sharp; the network then sees flat grey there. Pages with almost no such tone
 * (most clean scans and digital pages) are left untouched.
 *
 * plus/tools/enhance-bench/tone.py is the same algorithm in Python, used to tune it on real pages.
 */
object Screentone {

    // Detector: smoothstep ranges of the four statistics
    private const val CHECKER_LOW = 4f
    private const val CHECKER_HIGH = 8f
    private const val SHARE_LOW = 0.14f
    private const val SHARE_HIGH = 0.22f
    private const val COHERENCE_LOW = 0.35f
    private const val COHERENCE_HIGH = 0.6f
    private const val GRADIENT_LOW = 40f
    private const val GRADIENT_HIGH = 60f

    /** Pages with less tone than this share of their area are left as they are. */
    const val MIN_SHARE = 0.015f

    // Bilateral filter
    private const val RADIUS = 2
    private const val SIGMA_SPACE = 1.5f
    private const val SIGMA_RANGE = 40f
    private const val PASSES = 2

    /** Tone mask (0–1) on a grid of half the page's size. */
    class Mask(val width: Int, val height: Int, val values: FloatArray) {
        /** Share of the page that is tone. */
        val share: Float = values.count { it > 0.5f }.toFloat() / values.size.coerceAtLeast(1)

        /** Mask at pixel ([x], [y]) of the full-size page (bilinear). */
        fun at(x: Int, y: Int): Float {
            val fx = ((x - 0.5f) / 2).coerceIn(0f, (width - 1).toFloat())
            val fy = ((y - 0.5f) / 2).coerceIn(0f, (height - 1).toFloat())
            val x0 = fx.toInt()
            val y0 = fy.toInt()
            val x1 = minOf(x0 + 1, width - 1)
            val y1 = minOf(y0 + 1, height - 1)
            val tx = fx - x0
            val ty = fy - y0
            val top = values[y0 * width + x0] * (1 - tx) + values[y0 * width + x1] * tx
            val bottom = values[y1 * width + x0] * (1 - tx) + values[y1 * width + x1] * tx
            return top * (1 - ty) + bottom * ty
        }
    }

    /**
     * Smooths the unresolvable tone of [pixels] (ARGB, [width]×[height]) in place.
     * Returns the share of the page that is tone, or `null` if there was too little to touch.
     */
    fun smooth(pixels: IntArray, width: Int, height: Int): Float? {
        val mask = detect(pixels, width, height)
        if (mask.share < MIN_SHARE) return null
        apply(pixels, width, height, mask)
        return mask.share
    }

    /** The tone mask of [pixels] (ARGB). */
    fun detect(pixels: IntArray, width: Int, height: Int): Mask {
        require(pixels.size == width * height && width > 0 && height > 0)
        val luma = FloatArray(pixels.size) { luma(pixels[it]).toFloat() }
        fun y(x: Int, y: Int) = luma[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]

        // Per-pixel products, averaged over 2×2 blocks into the half grid
        val hw = (width + 1) / 2
        val hh = (height + 1) / 2
        val checker = FloatArray(hw * hh)
        val fine = FloatArray(hw * hh)
        val jxx = FloatArray(hw * hh)
        val jyy = FloatArray(hw * hh)
        val jxy = FloatArray(hw * hh)
        for (cy in 0 until hh) {
            for (cx in 0 until hw) {
                var c = 0f
                var f = 0f
                var xx = 0f
                var yy = 0f
                var xy = 0f
                for (d in 0 until 4) {
                    val px = minOf(cx * 2 + (d and 1), width - 1)
                    val py = minOf(cy * 2 + (d shr 1), height - 1)
                    val hx = (2 * y(px, py) - y(px - 1, py) - y(px + 1, py)) / 4
                    val hy = (2 * y(px, py) - y(px, py - 1) - y(px, py + 1)) / 4
                    val hxUp = (2 * y(px, py - 1) - y(px - 1, py - 1) - y(px + 1, py - 1)) / 4
                    val hxDown = (2 * y(px, py + 1) - y(px - 1, py + 1) - y(px + 1, py + 1)) / 4
                    val k = (2 * hx - hxUp - hxDown) / 4
                    val gx = (y(px + 1, py) - y(px - 1, py)) / 2
                    val gy = (y(px, py + 1) - y(px, py - 1)) / 2
                    c += k * k
                    f += hx * hx + hy * hy
                    xx += gx * gx
                    yy += gy * gy
                    xy += gx * gy
                }
                val i = cy * hw + cx
                checker[i] = c / 4
                fine[i] = f / 4
                jxx[i] = xx / 4
                jyy[i] = yy / 4
                jxy[i] = xy / 4
            }
        }
        val tmp = FloatArray(hw * hh)
        for (map in arrayOf(checker, fine, jxx, jyy, jxy)) {
            box(map, hw, hh, 2, tmp)
            box(map, hw, hh, 1, tmp)
        }

        val score = checker // reused
        for (i in score.indices) {
            val ec = checker[i]
            val sum = jxx[i] + jyy[i]
            val diff = jxx[i] - jyy[i]
            val coherence = (diff * diff + 4 * jxy[i] * jxy[i]) / ((sum + 1) * (sum + 1))
            score[i] = smoothstep(CHECKER_LOW, CHECKER_HIGH, sqrt(ec)) *
                smoothstep(SHARE_LOW, SHARE_HIGH, ec / (fine[i] + 25)) *
                (1 - smoothstep(COHERENCE_LOW, COHERENCE_HIGH, coherence)) *
                (1 - smoothstep(GRADIENT_LOW, GRADIENT_HIGH, sqrt(sum)))
        }
        // Opening drops specks, then the edges are feathered
        extreme(score, hw, hh, tmp, max = false)
        extreme(score, hw, hh, tmp, max = true)
        box(score, hw, hh, 1, tmp)
        box(score, hw, hh, 1, tmp)
        return Mask(hw, hh, score)
    }

    /** Bilateral-filters [pixels] where [mask] says tone, blending by the mask. */
    fun apply(pixels: IntArray, width: Int, height: Int, mask: Mask) {
        val spatial = FloatArray((2 * RADIUS + 1) * (2 * RADIUS + 1)) { i ->
            val dx = i % (2 * RADIUS + 1) - RADIUS
            val dy = i / (2 * RADIUS + 1) - RADIUS
            exp(-(dx * dx + dy * dy) / (2 * SIGMA_SPACE * SIGMA_SPACE))
        }
        val range = FloatArray(511) { i ->
            val d = (i - 255).toFloat()
            exp(-(d * d) / (2 * SIGMA_RANGE * SIGMA_RANGE))
        }

        // Every pass but the last needs its result around the tone too: half cells near any tone
        val near = BooleanArray(mask.values.size)
        val reach = (PASSES - 1) * RADIUS / 2 + 2
        for (cy in 0 until mask.height) {
            for (cx in 0 until mask.width) {
                if (mask.values[cy * mask.width + cx] <= 0f) continue
                for (ny in maxOf(0, cy - reach)..minOf(mask.height - 1, cy + reach)) {
                    for (nx in maxOf(0, cx - reach)..minOf(mask.width - 1, cx + reach)) {
                        near[ny * mask.width + nx] = true
                    }
                }
            }
        }

        var source = pixels
        repeat(PASSES - 1) {
            val next = source.copyOf()
            for (y in 0 until height) {
                for (x in 0 until width) {
                    if (near[(y / 2) * mask.width + x / 2]) {
                        next[y * width + x] = filtered(source, width, height, x, y, spatial, range)
                    }
                }
            }
            source = next
        }
        // Last pass straight into the page: it reads only [source], a copy
        for (y in 0 until height) {
            for (x in 0 until width) {
                val m = mask.at(x, y)
                if (m <= 0f) continue
                val i = y * width + x
                val f = filtered(source, width, height, x, y, spatial, range)
                pixels[i] = blend(f, pixels[i], m)
            }
        }
    }

    /** One bilateral sample at ([x], [y]): range weights from luma, applied to each channel. */
    private fun filtered(
        src: IntArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        spatial: FloatArray,
        range: FloatArray,
    ): Int {
        val center = luma(src[y * width + x])
        var r = 0f
        var g = 0f
        var b = 0f
        var total = 0f
        var k = 0
        for (dy in -RADIUS..RADIUS) {
            val row = (y + dy).coerceIn(0, height - 1) * width
            for (dx in -RADIUS..RADIUS) {
                val p = src[row + (x + dx).coerceIn(0, width - 1)]
                val w = spatial[k++] * range[luma(p) - center + 255]
                r += w * ((p shr 16) and 0xFF)
                g += w * ((p shr 8) and 0xFF)
                b += w * (p and 0xFF)
                total += w
            }
        }
        return (src[y * width + x] and 0xFF000000.toInt()) or
            (round(r / total) shl 16) or (round(g / total) shl 8) or round(b / total)
    }

    private fun blend(filtered: Int, original: Int, m: Float): Int {
        fun channel(shift: Int): Int {
            val f = (filtered shr shift) and 0xFF
            val o = (original shr shift) and 0xFF
            return round(m * f + (1 - m) * o)
        }
        return (original and 0xFF000000.toInt()) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun round(v: Float): Int = floor(v + 0.5f).toInt().coerceIn(0, 255)

    /** Box mean of radius [r] in place (edges repeated), rows then columns. */
    private fun box(a: FloatArray, w: Int, h: Int, r: Int, tmp: FloatArray) {
        val n = (2 * r + 1).toFloat()
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0f
                for (d in -r..r) s += a[y * w + (x + d).coerceIn(0, w - 1)]
                tmp[y * w + x] = s / n
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0f
                for (d in -r..r) s += tmp[(y + d).coerceIn(0, h - 1) * w + x]
                a[y * w + x] = s / n
            }
        }
    }

    /** 3×3 minimum or maximum in place (edges repeated). */
    private fun extreme(a: FloatArray, w: Int, h: Int, tmp: FloatArray, max: Boolean) {
        fun pick(p: Float, q: Float) = if (max) maxOf(p, q) else minOf(p, q)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val row = y * w
                tmp[row + x] = pick(pick(a[row + maxOf(x - 1, 0)], a[row + x]), a[row + minOf(x + 1, w - 1)])
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                a[y * w + x] = pick(
                    pick(tmp[maxOf(y - 1, 0) * w + x], tmp[y * w + x]),
                    tmp[minOf(y + 1, h - 1) * w + x],
                )
            }
        }
    }

    private fun smoothstep(low: Float, high: Float, x: Float): Float {
        val t = ((x - low) / (high - low)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    /** Same weights as PaperWhitening. */
    private fun luma(p: Int): Int = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
}
