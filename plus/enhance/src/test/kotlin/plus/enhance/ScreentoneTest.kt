package plus.enhance

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sign
import kotlin.math.sqrt

class ScreentoneTest {

    private val w = 160
    private val h = 120

    /** A grey page drawn by [tone] (0–255 per pixel). */
    private fun page(tone: (x: Int, y: Int) -> Double): IntArray = IntArray(w * h) { i ->
        val v = floor(tone(i % w, i / w) + 0.5).toInt().coerceIn(0, 255)
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    private fun wave(f: Double, v: Int) = cos(2 * PI * f * v)

    /**
     * Screentone as it comes out of a scan of ~830 px width: dots on a grid near the pixel pitch
     * (frequency 0.44 per pixel in both directions), soft, low contrast. The network makes "worms" of it.
     */
    private fun fineTone(x: Int, y: Int) = 170 + 40 * wave(0.44, x) * wave(0.44, y)

    private fun grey(pixels: IntArray, x: Int, y: Int) = pixels[y * w + x] and 0xFF

    /** Standard deviation of the grey inside the page, 20 px from the edges. */
    private fun spread(pixels: IntArray, xs: IntRange = 20 until w - 20): Double {
        val values = (20 until h - 20).flatMap { y -> xs.map { x -> grey(pixels, x, y).toDouble() } }
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    @Test
    fun `fine low-contrast screentone is found and smoothed to its grey`() {
        val pixels = page(::fineTone)
        val before = spread(pixels)
        val mean = pixels.map { it and 0xFF }.average()

        val share = Screentone.smooth(pixels, w, h).shouldNotBeNull()

        share shouldBeGreaterThan 0.9f
        spread(pixels) shouldBeLessThan before / 10
        abs(pixels.map { it and 0xFF }.average() - mean) shouldBeLessThan 1.0
    }

    @Test
    fun `lines crossing the tone stay sharp`() {
        val pixels = page { x, y -> if (x in 78..81) 10.0 else fineTone(x, y) }

        Screentone.smooth(pixels, w, h).shouldNotBeNull()

        for (y in 20 until h - 20) {
            for (x in 78..81) grey(pixels, x, y) shouldBeLessThan 20
        }
        spread(pixels, 30 until 70) shouldBeLessThan 3.0
    }

    @Test
    fun `crisp coarse screentone is left to the network`() {
        val pixels = page { x, y -> 140 + 110 * sign(wave(0.15, x) * wave(0.15, y)) }
        val original = pixels.copyOf()

        Screentone.smooth(pixels, w, h).shouldBeNull()
        pixels.toList() shouldContainExactly original.toList()
    }

    @Test
    fun `hatching is not screentone`() {
        // Fine vertical lines have no checker pattern; fine diagonal ones run one way (coherent)
        Screentone.detect(page { x, _ -> 170 + 40 * wave(0.44, x) }, w, h).share shouldBe 0f
        Screentone.detect(page { x, y -> 170 + 40 * wave(0.3, x + y) }, w, h).share shouldBe 0f
    }

    @Test
    fun `line art, flat grey and white paper are left alone`() {
        val lines = page { x, y -> if (abs(((x - y / 2 + 1000) % 9) - 4.5) < 1.2) 20.0 else 250.0 }
        val flat = page { _, _ -> 180.0 }
        val white = page { _, _ -> 255.0 }
        for (pixels in listOf(lines, flat, white)) {
            val original = pixels.copyOf()
            Screentone.smooth(pixels, w, h).shouldBeNull()
            pixels.toList() shouldContainExactly original.toList()
        }
    }

    @Test
    fun `a page with only a speck of tone is left alone`() {
        // Tone in a 12×12 corner: under 1.5 % of the page
        val pixels = page { x, y -> if (x < 12 && y < 12) fineTone(x, y) else 250.0 }
        Screentone.detect(pixels, w, h).share shouldBeLessThan Screentone.MIN_SHARE
        Screentone.smooth(pixels, w, h).shouldBeNull()
    }

    @Test
    fun `tiny and odd-sized pages do not break`() {
        for ((pw, ph) in listOf(1 to 1, 7 to 5, 2 to 9)) {
            val pixels = IntArray(pw * ph) { i ->
                val v = fineTone(i % pw, i / pw).toInt()
                (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            val mask = Screentone.detect(pixels, pw, ph)
            mask.width shouldBe (pw + 1) / 2
            mask.height shouldBe (ph + 1) / 2
            Screentone.smooth(pixels, pw, ph)
        }
    }

    @Test
    fun `alpha is kept`() {
        val pixels = page(::fineTone).map { (it and 0x00FFFFFF) or (0x80 shl 24) }.toIntArray()
        Screentone.smooth(pixels, w, h).shouldNotBeNull()
        pixels.all { (it ushr 24) == 0x80 } shouldBe true
    }
}
