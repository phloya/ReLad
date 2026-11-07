package plus.enhance

import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.random.Random

class PaperWhiteningTest {

    private fun rgb(r: Int, g: Int, b: Int): Int {
        fun c(v: Int) = v.coerceIn(0, 255)
        return (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }

    /** A page: [paperShare] of paper with a little noise, the rest ink lines in [ink]. */
    private fun page(paper: Triple<Int, Int, Int>, ink: Int = rgb(25, 25, 25), paperShare: Double = 0.8): IntArray {
        val random = Random(1)
        return IntArray(400 * 600) {
            if (random.nextDouble() < paperShare) {
                val n = random.nextInt(-4, 5)
                rgb(paper.first + n, paper.second + n, paper.third + n)
            } else {
                ink
            }
        }
    }

    private fun IntArray.red(i: Int) = (this[i] shr 16) and 0xFF
    private fun IntArray.blue(i: Int) = this[i] and 0xFF

    @Test
    fun `yellowed grey paper becomes white, ink stays dark`() {
        val pixels = page(Triple(225, 218, 200))
        val paperIndex = pixels.indexOfFirst { (it and 0xFF) > 150 }
        val inkIndex = pixels.indexOfFirst { (it and 0xFF) < 50 }

        val gains = PaperWhitening.analyze(pixels).shouldNotBeNull()
        PaperWhitening.apply(pixels, gains)

        pixels.red(paperIndex) shouldBeGreaterThanOrEqual 248
        pixels.blue(paperIndex) shouldBeGreaterThanOrEqual 248
        pixels.red(inkIndex) shouldBeLessThan 40
    }

    @Test
    fun `white paper is left alone`() {
        PaperWhitening.analyze(page(Triple(253, 253, 253))) shouldBe null
    }

    @Test
    fun `dark pages are left alone`() {
        PaperWhitening.analyze(page(Triple(90, 90, 90))) shouldBe null
        PaperWhitening.analyze(page(Triple(230, 230, 230), paperShare = 0.0)) shouldBe null
    }

    @Test
    fun `colour pages are left alone`() {
        val random = Random(2)
        val colours = intArrayOf(rgb(220, 60, 60), rgb(60, 160, 220), rgb(240, 200, 60))
        val pixels = page(Triple(230, 225, 215)).map { pixel ->
            val coloured = random.nextDouble() < 0.2
            if (coloured) colours[random.nextInt(3)] else pixel
        }

        PaperWhitening.analyze(pixels.toIntArray()) shouldBe null
    }

    @Test
    fun `alpha is kept`() {
        val pixels = intArrayOf(0x80E0E0E0.toInt())
        PaperWhitening.apply(pixels, PaperWhitening.Gains(1.1f, 1.1f, 1.1f))

        (pixels[0] ushr 24) shouldBe 0x80
        pixels[0] and 0xFF shouldBe 246
    }
}
