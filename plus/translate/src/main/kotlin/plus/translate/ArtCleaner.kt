package plus.translate

/**
 * Removes light letters with their dark outline from the art behind them (narration and titles
 * over a scene): the letters' pixels are filled in from the art around them, layer by layer from
 * the edge inwards. The art between the letters stays; where the letters were it is a little soft,
 * and the translation, drawn the same way, covers most of it.
 *
 * Works on plain ARGB pixels so it can be tested without Android.
 */
object ArtCleaner {

    /**
     * Cleans the light letters inside [box] from [pixels] in place. [outline] is how far around
     * a letter its dark outline reaches, in pixels.
     */
    fun clean(pixels: IntArray, width: Int, height: Int, box: Box, outline: Int) {
        val area = box.grown(outline + 1, width, height)
        val w = area.width
        val h = area.height
        if (w <= 0 || h <= 0) return

        // The letters themselves, inside the box: light, with their dark outline right next to them
        // (light smoke or sky has none)
        val light = BooleanArray(w * h)
        val dark = BooleanArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val lum = BubbleEraser.luminance(pixels[(area.top + y) * width + area.left + x])
                val pageX = area.left + x
                val pageY = area.top + y
                light[y * w + x] =
                    lum >= LIGHT_LETTER && pageX in box.left until box.right && pageY in box.top until box.bottom
                dark[y * w + x] = lum < OUTLINE_DARK
            }
        }
        val nearDark = dilate(dark, w, h, LETTER_TO_OUTLINE)
        val letter = BooleanArray(w * h) { light[it] && nearDark[it] }
        // ...and their dark outline around them; the art's mid tones nearby stay
        val near = dilate(letter, w, h, outline)
        val mask = BooleanArray(w * h) { i -> letter[i] || (near[i] && dark[i]) }

        val known = BooleanArray(w * h) { !mask[it] }
        var unknown = mask.count { it }
        val layer = ArrayList<Int>()
        val values = ArrayList<Int>()
        while (unknown > 0) {
            layer.clear()
            values.clear()
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    if (known[i]) continue
                    var r = 0
                    var g = 0
                    var b = 0
                    var n = 0
                    for (dy in -1..1) {
                        val ny = y + dy
                        if (ny < 0 || ny >= h) continue
                        for (dx in -1..1) {
                            val nx = x + dx
                            if (nx < 0 || nx >= w || !known[ny * w + nx]) continue
                            val color = pixels[(area.top + ny) * width + area.left + nx]
                            r += (color shr 16) and 0xFF
                            g += (color shr 8) and 0xFF
                            b += color and 0xFF
                            n++
                        }
                    }
                    if (n == 0) continue
                    layer += i
                    values += (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
                }
            }
            // Nothing known anywhere around (the whole area is letters): leave it
            if (layer.isEmpty()) return
            for (k in layer.indices) {
                val i = layer[k]
                pixels[(area.top + i / w) * width + area.left + i % w] = values[k]
                known[i] = true
            }
            unknown -= layer.size
        }
    }

    /** Every pixel within [radius] (a square) of a set one. */
    internal fun dilate(source: BooleanArray, w: Int, h: Int, radius: Int): BooleanArray {
        if (radius <= 0) return source.copyOf()
        // Separable square dilation: rows, then columns
        val rows = BooleanArray(source.size)
        for (y in 0 until h) {
            // Two passes per row: the nearest set pixel on the left, then on the right
            var last = -radius - 1
            for (x in 0 until w) {
                if (source[y * w + x]) last = x
                if (x - last <= radius) rows[y * w + x] = true
            }
            var next = w + radius + 1
            for (x in w - 1 downTo 0) {
                if (source[y * w + x]) next = x
                if (next - x <= radius) rows[y * w + x] = true
            }
        }
        val result = BooleanArray(source.size)
        for (x in 0 until w) {
            var last = -radius - 1
            for (y in 0 until h) {
                if (rows[y * w + x]) last = y
                if (y - last <= radius) result[y * w + x] = true
            }
            var next = h + radius + 1
            for (y in h - 1 downTo 0) {
                if (rows[y * w + x]) next = y
                if (next - y <= radius) result[y * w + x] = true
            }
        }
        return result
    }

    private const val LIGHT_LETTER = 160

    /** A light letter's outline is this close to it (pixels). */
    private const val LETTER_TO_OUTLINE = 3

    /** The outline of light letters: darker than this. */
    private const val OUTLINE_DARK = 100
}
