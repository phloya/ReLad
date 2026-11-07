package plus.colorize

import kotlin.math.floor

/** An image as ARGB ints, without Android (so the color math runs in JVM tests). */
class Raster(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(pixels.size == width * height) { "${pixels.size} pixels for $width×$height" }
    }

    fun gray(): Plane = Plane(width, height, FloatArray(pixels.size) { luma(pixels[it]) })

    /** Chroma planes (Cb, Cr of BT.601), in the same range as luma (about -128..128). */
    fun chroma(): Pair<Plane, Plane> {
        val cb = FloatArray(pixels.size)
        val cr = FloatArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16 and 0xFF).toFloat()
            val g = (p shr 8 and 0xFF).toFloat()
            val b = (p and 0xFF).toFloat()
            cb[i] = -0.168736f * r - 0.331264f * g + 0.5f * b
            cr[i] = 0.5f * r - 0.418688f * g - 0.081312f * b
        }
        return Plane(width, height, cb) to Plane(width, height, cr)
    }

    companion object {
        fun luma(p: Int): Float = 0.299f * (p shr 16 and 0xFF) + 0.587f * (p shr 8 and 0xFF) + 0.114f * (p and 0xFF)

        fun rgb(r: Float, g: Float, b: Float): Int {
            fun c(v: Float) = v.toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (c(r + 0.5f) shl 16) or (c(g + 0.5f) shl 8) or c(b + 0.5f)
        }
    }
}

/** One channel of floats. */
class Plane(val width: Int, val height: Int, val values: FloatArray) {

    operator fun get(x: Int, y: Int): Float = values[y * width + x]

    /** Bilinear sample at pixel coordinates (pixel centres at +0.5); outside the plane the edge repeats. */
    fun sample(x: Float, y: Float): Float {
        val fx = (x - 0.5f).coerceIn(0f, (width - 1).toFloat())
        val fy = (y - 0.5f).coerceIn(0f, (height - 1).toFloat())
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val x1 = minOf(x0 + 1, width - 1)
        val y1 = minOf(y0 + 1, height - 1)
        val ax = fx - x0
        val ay = fy - y0
        val top = this[x0, y0] * (1 - ax) + this[x1, y0] * ax
        val bottom = this[x0, y1] * (1 - ax) + this[x1, y1] * ax
        return top * (1 - ay) + bottom * ay
    }

    /** Area-averaged copy of [newWidth]×[newHeight] (for shrinking; each output pixel averages its input area). */
    fun resized(newWidth: Int, newHeight: Int): Plane {
        val out = FloatArray(newWidth * newHeight)
        val sx = width.toFloat() / newWidth
        val sy = height.toFloat() / newHeight
        for (y in 0 until newHeight) {
            val y0 = (y * sy).toInt()
            val y1 = maxOf(y0 + 1, minOf(height, ((y + 1) * sy).toInt()))
            for (x in 0 until newWidth) {
                val x0 = (x * sx).toInt()
                val x1 = maxOf(x0 + 1, minOf(width, ((x + 1) * sx).toInt()))
                var sum = 0f
                for (yy in y0 until y1) for (xx in x0 until x1) sum += this[xx, yy]
                out[y * newWidth + x] = sum / ((y1 - y0) * (x1 - x0))
            }
        }
        return Plane(newWidth, newHeight, out)
    }

    /** Box blur with [radius] pixels, edges repeated. */
    fun blurred(radius: Int): Plane {
        if (radius <= 0) return this
        val horizontal = FloatArray(values.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0f
                for (d in -radius..radius) sum += this[(x + d).coerceIn(0, width - 1), y]
                horizontal[y * width + x] = sum / (2 * radius + 1)
            }
        }
        val out = FloatArray(values.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0f
                for (d in -radius..radius) sum += horizontal[(y + d).coerceIn(0, height - 1) * width + x]
                out[y * width + x] = sum / (2 * radius + 1)
            }
        }
        return Plane(width, height, out)
    }

    /** Strength of edges (lines): |dx| + |dy|, after a light blur so a 1 px shift still overlaps. */
    fun edges(): Plane {
        val smooth = blurred(1)
        val out = FloatArray(values.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val dx = smooth[minOf(x + 1, width - 1), y] - smooth[maxOf(x - 1, 0), y]
                val dy = smooth[x, minOf(y + 1, height - 1)] - smooth[x, maxOf(y - 1, 0)]
                out[y * width + x] = kotlin.math.abs(dx) + kotlin.math.abs(dy)
            }
        }
        return Plane(width, height, out)
    }
}
