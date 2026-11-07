package plus.translate

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.math.abs

class LetteringTest {

    /** Every character 0.6 of the size wide, like an average comic font. */
    private val measurer = object : TextMeasurer {
        override fun width(text: String) = text.length * 0.6f
        override val capHeight = 0.7f
        override val xHeight = 0.5f
        override val lineHeight = 1.3f
    }

    private fun pieces(text: String) = TextPieces.of(text, measurer, Locale.ENGLISH)

    @Test
    fun `lines fit their own widths and are filled evenly`() {
        val text = pieces("YOU MUST NOT VALUE YOUR LIFE AT ALL")
        // Size 10: a character is 6 px; narrow, wide, narrow lines as in a round bubble
        val ends = LineBreaker.split(text, 10f, floatArrayOf(90f, 130f, 90f)).shouldNotBeNull()
        ends.size shouldBe 3
        var start = 0
        val widths = floatArrayOf(90f, 130f, 90f)
        for (k in ends.indices) {
            (text.width(start, ends[k]) * 10 <= widths[k]) shouldBe true
            start = ends[k]
        }
        ends.last() shouldBe text.size
    }

    @Test
    fun `text too long for the lines does not fit`() {
        LineBreaker.split(pieces("A VERY LONG SENTENCE THAT CANNOT FIT"), 10f, floatArrayOf(40f, 40f)).shouldBeNull()
    }

    @Test
    fun `text is set in a round bubble around the original's place, centred`() {
        // A circle of radius 60 around (100, 100)
        val rows = IntArray(121 * 2)
        for (i in 0..120) {
            val dy = i - 60
            val half = kotlin.math.sqrt((60.0 * 60 - dy * dy)).toInt()
            rows[i * 2] = 100 - half
            rows[i * 2 + 1] = 100 + half
        }
        val room = Room.ofRows(40, rows)
        val style = TextBlockLayout.Style(measurer, lineHeight = 1.3f, padShare = 0.1f, minPad = 0.5f)
        val block = TextBlockLayout.place(pieces("I'LL SHAKE THE SOUL OUT OF YOU"), room, 100f, 10f, 7f, style)
            .shouldNotBeNull()

        block.size shouldBe 10f
        (block.lines.size >= 2) shouldBe true
        for (line in block.lines) {
            (abs(line.centerX - 100f) < 2f) shouldBe true
            val half = measurer.width(line.text) * block.size / 2
            val inside = room.between(line.baseline - 7f, line.baseline).shouldNotBeNull()
            (line.centerX - half >= inside.first && line.centerX + half <= inside.second) shouldBe true
        }
        // The block's middle is where the original text was
        val middle = (block.lines.first().baseline + block.lines.last().baseline) / 2 - 3.5f
        (abs(middle - 100f) < 10f) shouldBe true
    }

    @Test
    fun `text that doesn't fit at the original's size is set smaller`() {
        val room = Room.of(Box(0, 0, 100, 30))
        val style = TextBlockLayout.Style(measurer, lineHeight = 1.3f, padShare = 0f, minPad = 0f)
        val block = TextBlockLayout.place(
            pieces("ONE TWO THREE FOUR FIVE SIX"),
            room,
            15f,
            12f,
            4f,
            style,
        ).shouldNotBeNull()
        (block.size < 12f) shouldBe true
    }

    @Test
    fun `letters are measured line by line`() {
        val width = 200
        val height = 120
        val pixels = IntArray(width * height) { -0x1 }
        // Three "lines" of letters: rows of 10 px tall strokes, 20 px apart
        for (line in 0 until 3) {
            for (x in 40 until 160 step 6) {
                for (y in 20 + line * 20 until 30 + line * 20) {
                    pixels[y * width + x] = -0x1000000
                    pixels[y * width + x + 1] = -0x1000000
                }
            }
        }
        val ink = InkMeasure.measure(
            pixels,
            width,
            height,
            Box(38, 18, 162, 72),
            lightLetters = false,
            vertical = false,
        )
            .shouldNotBeNull()
        ink.lines shouldBe 3
        ink.core shouldBe 10f
        ink.pitch shouldBe 20f
    }

    @Test
    fun `light letters are cleaned from the art with their outline`() {
        val width = 100
        val height = 60
        val gray = 0xFF606060.toInt()
        val pixels = IntArray(width * height) { gray }
        // A white letter stroke (thin, as letters are) with a black outline around it
        for (y in 26 until 34) for (x in 28 until 52) pixels[y * width + x] = -0x1000000
        for (y in 28 until 32) for (x in 30 until 50) pixels[y * width + x] = -0x1
        ArtCleaner.clean(pixels, width, height, Box(30, 28, 50, 32), outline = 3)
        for (y in 26 until 34) {
            for (x in 28 until 52) {
                (abs(BubbleEraser.luminance(pixels[y * width + x]) - 0x60) < 8) shouldBe true
            }
        }
    }

    @Test
    fun `case follows the original`() {
        LetterCase.upper("ТЫ, ДОЛЖНО БЫТЬ, СОВСЕМ НЕ ЦЕНИШЬ", RegionType.Bubble) shouldBe true
        LetterCase.upper("Некоторые из них сражались за себя.", RegionType.Caption) shouldBe false
        // No case in Japanese: bubbles get capitals, signs stay as they are
        LetterCase.upper("何だと!?", RegionType.Bubble) shouldBe true
        LetterCase.upper("出口", RegionType.Other) shouldBe false
        LetterCase.apply("oʻzbekcha", upper = true, Locale.forLanguageTag("uz")) shouldBe "OʻZBEKCHA"
    }
}
