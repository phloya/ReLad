package plus.panels

import kotlin.math.abs
import kotlin.random.Random

/** Draws synthetic pages for the tests: panels with borders and "art", balloons, gutters. */
class TestPage(val width: Int, val height: Int, background: Int = 255) {

    private val pixels = ByteArray(width * height) { background.toByte() }
    private val random = Random(7)

    fun set(x: Int, y: Int, value: Int) {
        if (x in 0 until width && y in 0 until height) pixels[y * width + x] = value.toByte()
    }

    fun fill(x0: Int, y0: Int, x1: Int, y1: Int, value: Int) {
        for (y in y0 until y1) for (x in x0 until x1) set(x, y, value)
    }

    /** A panel: a black border, white inside, a few strokes of "art" and a bit of screentone. */
    fun panel(x0: Int, y0: Int, x1: Int, y1: Int, border: Int = 4, ink: Int = 0, paper: Int = 255) {
        fill(x0, y0, x1, y1, ink)
        fill(x0 + border, y0 + border, x1 - border, y1 - border, paper)
        repeat(6) {
            val ax = random.nextInt(x0 + border, x1 - border)
            val ay = random.nextInt(y0 + border, y1 - border)
            val bx = random.nextInt(x0 + border, x1 - border)
            val by = random.nextInt(y0 + border, y1 - border)
            line(ax, ay, bx, by, 3, ink)
        }
        val tx = random.nextInt(x0 + border, (x0 + x1) / 2)
        val ty = random.nextInt(y0 + border, (y0 + y1) / 2)
        for (y in ty until minOf(ty + (y1 - y0) / 4, y1 - border)) {
            for (x in tx until minOf(tx + (x1 - x0) / 4, x1 - border)) {
                if ((x + y) % 4 == 0) set(x, y, 90)
            }
        }
    }

    /**
     * A filled ellipse with a black outline: a speech balloon. Artists often leave a ring of
     * white paper ([halo] px) between a balloon and the art around it.
     */
    fun balloon(cx: Int, cy: Int, rx: Int, ry: Int, halo: Int = 0) {
        if (halo > 0) ellipse(cx, cy, rx + 3 + halo, ry + 3 + halo, 255)
        for (y in cy - ry - 3..cy + ry + 3) {
            for (x in cx - rx - 3..cx + rx + 3) {
                val d = sq((x - cx).toFloat() / rx) + sq((y - cy).toFloat() / ry)
                val outer = sq((x - cx).toFloat() / (rx + 3)) + sq((y - cy).toFloat() / (ry + 3))
                if (d <= 1f) {
                    set(x, y, 255)
                } else if (outer <= 1f) {
                    set(x, y, 0)
                }
            }
        }
        line(cx - rx / 2, cy - 4, cx + rx / 2, cy - 4, 3, 0)
        line(cx - rx / 2, cy + 6, cx + rx / 3, cy + 6, 3, 0)
    }

    fun ellipse(cx: Int, cy: Int, rx: Int, ry: Int, value: Int) {
        for (y in cy - ry..cy + ry) {
            for (x in cx - rx..cx + rx) {
                if (sq((x - cx).toFloat() / rx) + sq((y - cy).toFloat() / ry) <= 1f) set(x, y, value)
            }
        }
    }

    fun line(x0: Int, y0: Int, x1: Int, y1: Int, thickness: Int, value: Int) {
        val steps = maxOf(abs(x1 - x0), abs(y1 - y0), 1)
        for (i in 0..steps) {
            val x = x0 + (x1 - x0) * i / steps
            val y = y0 + (y1 - y0) * i / steps
            fill(x - thickness / 2, y - thickness / 2, x + thickness / 2 + 1, y + thickness / 2 + 1, value)
        }
    }

    fun noise(amount: Int) {
        for (i in pixels.indices) {
            val p = (pixels[i].toInt() and 0xFF) + random.nextInt(-amount, amount + 1)
            pixels[i] = p.coerceIn(0, 255).toByte()
        }
    }

    fun image() = GrayImage(width, height, pixels.copyOf())

    private fun sq(v: Float) = v * v
}

/** Center of a found box, in pixels of a page of the given size. */
fun Box.center(w: Int, h: Int) = (centerX * w).toInt() to (centerY * h).toInt()

/** The found panel that contains the point, or -1. */
fun List<Box>.indexAt(x: Float, y: Float) = indexOfFirst { x in it.left..it.right && y in it.top..it.bottom }
