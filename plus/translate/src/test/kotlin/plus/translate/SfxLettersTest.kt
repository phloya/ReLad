package plus.translate

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.math.abs

class SfxLettersTest {

    private val black = -0x1000000
    private val white = -0x1
    private val gray = 0xFF808080.toInt()

    /** Diagonal stripes, as hatching on the art. */
    private fun hatched(width: Int, height: Int) = IntArray(width * height) { i ->
        if ((i % width + i / width) % 6 < 2) black else gray
    }

    /** A letter: a filled block with an edge of [edge] pixels of the other tone. */
    private fun letter(pixels: IntArray, width: Int, x: Int, y: Int, w: Int, h: Int, fill: Int, edge: Int) {
        val outline = if (fill == black) white else black
        for (yy in y - edge until y + h + edge) {
            for (xx in x - edge until x + w + edge) {
                pixels[yy * width + xx] =
                    outline
            }
        }
        for (yy in y until y + h) for (xx in x until x + w) pixels[yy * width + xx] = fill
    }

    @Test
    fun `dark letters with a white edge are found on the art`() {
        val width = 300
        val height = 200
        val pixels = hatched(width, height)
        for (k in 0 until 4) letter(pixels, width, 60 + k * 45, 80, 16, 40, black, 4)
        val letters = SfxLetters.find(pixels, width, height, Box(50, 70, 250, 130), "БУММ").shouldNotBeNull()
        letters.light shouldBe false
        letters.angle shouldBe 0f
        (abs(letters.letterHeight - 40) <= 2) shouldBe true
        (abs(letters.edgeWidth - 4) <= 1) shouldBe true
        letters.lines.size shouldBe 1
        // The letters and their edge are erased, the art beside them isn't
        val mask = letters.mask
        val area = letters.area
        mask[(100 - area.top) * area.width + (68 - area.left)] shouldBe true
        mask[(100 - area.top) * area.width + (58 - area.left)] shouldBe true
        mask[(100 - area.top) * area.width + (90 - area.left)] shouldBe false
    }

    @Test
    fun `white letters with a black outline, on a slant`() {
        val width = 320
        val height = 240
        val pixels = hatched(width, height)
        // Each letter a little lower than the one before: a line going down to the right
        for (k in 0 until 5) letter(pixels, width, 40 + k * 45, 60 + k * 15, 18, 40, white, 4)
        val letters = SfxLetters.find(pixels, width, height, Box(30, 50, 280, 180), "SLAMS").shouldNotBeNull()
        letters.light shouldBe true
        // atan(15 / 45) is about 18°
        (abs(letters.angle - 18.4f) < 4f) shouldBe true
    }

    @Test
    fun `a sound on two lines keeps its two lines`() {
        val width = 300
        val height = 260
        val pixels = hatched(width, height)
        for (k in 0 until 3) letter(pixels, width, 60 + k * 40, 60, 16, 36, black, 4)
        for (k in 0 until 3) letter(pixels, width, 80 + k * 40, 140, 16, 36, black, 4)
        val letters = SfxLetters.find(pixels, width, height, Box(50, 50, 220, 190), "ТОП ТОП").shouldNotBeNull()
        letters.angle shouldBe 0f
        letters.lines.size shouldBe 2
        (letters.lines[0].centerY < letters.lines[1].centerY) shouldBe true
    }

    @Test
    fun `thin lines of the art are not letters`() {
        val width = 200
        val height = 150
        val pixels = hatched(width, height)
        SfxLetters.find(pixels, width, height, Box(40, 40, 160, 110), "BAM").shouldBeNull()
    }

    @Test
    fun `the art behind is drawn on from the art around`() {
        val width = 120
        val height = 120
        // Vertical stripes, 3 dark and 3 light pixels
        val truth = IntArray(width * height) { i -> if (i % width % 6 < 3) black else white }
        val pixels = truth.copyOf()
        val area = Box(40, 40, 80, 80)
        val hole = BooleanArray(area.width * area.height) { true }
        for (y in area.top until area.bottom) for (x in area.left until area.right) pixels[y * width + x] = gray
        Inpaint.fill(pixels, width, height, area, hole)
        var error = 0L
        for (y in area.top until area.bottom) {
            for (x in area.left until area.right) {
                error +=
                    abs(BubbleEraser.luminance(pixels[y * width + x]) - BubbleEraser.luminance(truth[y * width + x]))
            }
        }
        // The stripes go on through the hole (a blur would be 127 off everywhere)
        (error / (area.width * area.height) < 20) shouldBe true
    }
}
