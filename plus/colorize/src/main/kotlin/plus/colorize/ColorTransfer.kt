package plus.colorize

/**
 * Puts the model's colors onto the original page.
 *
 * Fine detail (lines, text, screentone) always comes from the original, so the model cannot blur
 * letters or move a line. From the colored image only two smooth things are taken: the color
 * (chroma) and the broad light and shade (luminance with the detail blurred out). Small
 * misplacements therefore do not show, and the page keeps its full resolution even when the
 * model answered with a small picture.
 */
class ColorTransfer private constructor(
    /** Original (u, v) → colored pixel, at the colored image's full size. */
    private val placement: Placement,
    private val cb: Plane,
    private val cr: Plane,
    /** Broad brightness change the coloring brings, on a coarse grid over the original page. */
    private val shade: Plane,
) {

    /** Colors one row of the original page in place. [row] holds ARGB pixels of row [y] of a [width]×[height] page. */
    fun applyRow(row: IntArray, y: Int, width: Int, height: Int) {
        val v = (y + 0.5f) / height
        val coloredY = placement.y(v)
        val shadeY = v * shade.height
        for (x in 0 until width) {
            val u = (x + 0.5f) / width
            val coloredX = placement.x(u)
            val luma = (Raster.luma(row[x]) + shade.sample(u * shade.width, shadeY)).coerceIn(0f, 255f)
            // Lines stay neutral: color fades out in the darkest tones
            val keep = minOf(1f, luma / DARK)
            val b = cb.sample(coloredX, coloredY) * keep
            val r = cr.sample(coloredX, coloredY) * keep
            val alpha = row[x] and 0xFF000000.toInt()
            row[x] = alpha or (
                Raster.rgb(
                    luma + 1.402f * r,
                    luma - 0.344136f * b - 0.714136f * r,
                    luma + 1.772f * b,
                ) and 0x00FFFFFF
                )
        }
    }

    companion object {
        /** Luma below this gets less color: black ink should stay black. */
        private const val DARK = 48f

        /** How much the broad shading is blurred, as a share of the small planes' width (~1–2% of the page). */
        private const val SHADE_BLUR = 0.015f

        /**
         * [originalSmall] and [coloredSmall] are the gray planes used for [Alignment], and
         * [smallPlacement] the placement found there; [colored] is the model's full image and
         * [fullPlacement] the same placement in its pixels.
         */
        fun create(
            originalSmall: Plane,
            coloredSmall: Plane,
            smallPlacement: Placement,
            colored: Raster,
            fullPlacement: Placement,
        ): ColorTransfer {
            val w = originalSmall.width
            val h = originalSmall.height
            val mapped = FloatArray(w * h)
            for (y in 0 until h) {
                val v = (y + 0.5f) / h
                for (x in 0 until w) {
                    val u = (x + 0.5f) / w
                    mapped[y * w + x] = coloredSmall.sample(smallPlacement.x(u), smallPlacement.y(v))
                }
            }
            val radius = maxOf(1, (w * SHADE_BLUR).toInt())
            val coloredBroad = Plane(w, h, mapped).blurred(radius)
            val originalBroad = originalSmall.blurred(radius)
            val shade = Plane(w, h, FloatArray(w * h) { coloredBroad.values[it] - originalBroad.values[it] })
            val (cb, cr) = colored.chroma()
            return ColorTransfer(fullPlacement, cb, cr, shade)
        }
    }
}

/** Pages that already have color are left alone. */
object ColorPage {
    /** A pixel this far from gray counts as colored; yellowed paper stays below it. */
    private const val COLORED = 40

    /** More colored pixels than this share makes a color page. */
    private const val SHARE = 0.02

    fun isColor(raster: Raster): Boolean {
        var colored = 0
        for (p in raster.pixels) {
            val r = p shr 16 and 0xFF
            val g = p shr 8 and 0xFF
            val b = p and 0xFF
            if (maxOf(r, g, b) - minOf(r, g, b) > COLORED) colored++
        }
        return colored > raster.pixels.size * SHARE
    }
}
