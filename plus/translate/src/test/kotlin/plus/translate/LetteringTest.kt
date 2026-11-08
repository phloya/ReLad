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
    fun `long Russian words are cut between syllables, short hyphenated ones are not`() {
        Hyphenation.split("ОКАЗЫВАЕТСЯ, ") shouldBe listOf("ОКА", "ЗЫ", "ВАЕТ", "СЯ, ")
        val parts = Hyphenation.split("ПОЗАИМСТВОВАТЬ!!!")
        parts shouldBe listOf("ПО", "ЗАИМ", "СТВО", "ВАТЬ!!!")
        // Every part has a vowel and two letters; no part starts with Ь, Ъ, Й
        for (part in parts) {
            (part.count { it.isLetter() } >= 2) shouldBe true
            part.any { it.lowercaseChar() in "аеёиоуыэюя" } shouldBe true
            (part.first().lowercaseChar() !in "ьъй") shouldBe true
        }
        Hyphenation.split("ДЕНЬ ") shouldBe listOf("ДЕНЬ ")
        Hyphenation.split("SOMETHING") shouldBe listOf("SOMETHING")
        // "что-то" stays on one line, and so does a short name ("Су-хо")
        pieces("ЧТО-ТО ДРУГОЕ").pieces.first() shouldBe "ЧТО-ТО "
        pieces("КАКОЙ СУ-ХО СМЫШЛЁНЫЙ").pieces[1] shouldBe "СУ-ХО "
        pieces("ЧТО-ТО КАКОЙ-ТО").pieces shouldBe listOf("ЧТО-ТО ", "КАКОЙ-ТО")
        // A long name with a short suffix may go over two lines at its hyphen
        pieces("ЦУКИШИМА-КУН.").pieces shouldBe listOf("ЦУКИШИМА-", "КУН.")
    }

    @Test
    fun `a word wider than the lines is cut with a hyphen`() {
        val text = TextPieces.of(
            "МНЕ НУЖНО ТЕБЯ ПОЗАИМСТВОВАТЬ!!!",
            measurer,
            Locale.forLanguageTag("ru"),
            hyphenate = true,
        )
        // Size 10: a character is 6 px; the long word (17 characters, 102 px) is wider than the lines
        val ends = LineBreaker.split(text, 10f, floatArrayOf(70f, 70f, 70f, 70f)).shouldNotBeNull()
        var start = 0
        val lines = ends.map { end -> text.line(start, end).also { start = end } }
        lines.any { it.endsWith("-") } shouldBe true
        lines.joinToString("") { it.removeSuffix("-") + " " }.replace(" ", "") shouldBe "МНЕНУЖНОТЕБЯПОЗАИМСТВОВАТЬ!!!"
        // Where the words fit, none is cut
        val roomy = LineBreaker.split(text, 10f, floatArrayOf(200f, 200f)).shouldNotBeNull()
        start = 0
        roomy.map { end -> text.line(start, end).also { start = end } }.none { it.endsWith("-") } shouldBe true
        // Only a word wider than the bubble is cut, and not the short ones beside it
        val onlyLong = TextPieces.of(
            "НУЖНО ТЕБЯ ПОЗАИМСТВОВАТЬ ЗАВТРА",
            measurer,
            Locale.forLanguageTag("ru"),
            hyphenate = true,
            cutWiderThan = 7 * 0.6f,
        )
        onlyLong.pieces.take(2) shouldBe listOf("НУЖНО ", "ТЕБЯ ")
        onlyLong.pieces.last() shouldBe "ЗАВТРА"
        (onlyLong.pieces.size > 4) shouldBe true
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
    fun `a text keeps off another text's place in a shared bubble`() {
        val room = Room.of(Box(0, 0, 300, 100))
        val shared = room.excluding(listOf(Box(200, 30, 260, 50)), anchorX = 80f, margin = 6)
        // Rows beside the other text stop before it; rows above and below are whole
        shared.between(40f, 41f)!!.second shouldBe 194f
        shared.between(5f, 6f)!!.second shouldBe 299f
        // Centred where its original was, not in the room's middle
        val style = TextBlockLayout.Style(measurer, lineHeight = 1.3f, padShare = 0f, minPad = 0f)
        val block = TextBlockLayout.place(pieces("SHORT"), shared, 40f, 10f, 7f, style, anchorX = 80f).shouldNotBeNull()
        block.lines.single().centerX shouldBe 80f
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

    @Test
    fun `a line is not put in quotation marks`() {
        LetterCase.withoutQuotes("«Леди Фарнезе...!!»", "LADY FARNESE ...!!") shouldBe "Леди Фарнезе...!!"
        LetterCase.withoutQuotes("«Эй!»!", "HEY!!") shouldBe "Эй!!"
        LetterCase.withoutQuotes("\"Hey!\"", "おい!") shouldBe "Hey!"
        // Quotes inside the line, or quoted in the original too, stay
        LetterCase.withoutQuotes("Он сказал «нет» и ушёл", "HE SAID \"NO\"") shouldBe "Он сказал «нет» и ушёл"
        LetterCase.withoutQuotes("«Да» или «нет»", "YES OR NO") shouldBe "«Да» или «нет»"
        LetterCase.withoutQuotes("«Нет»", "\"NO\"") shouldBe "«Нет»"
    }

    @Test
    fun `dots and marks are left as drawn`() {
        val width = 200
        val height = 200
        val pixels = IntArray(width * height) { -0x1 }
        val regions = listOf(
            TextRegion(RegionType.Bubble, Box(20, 20, 60, 80), "……!!", "……!!"),
            TextRegion(RegionType.Bubble, Box(80, 20, 120, 80), "……ッ!!", "ч-!!"),
            TextRegion(RegionType.Bubble, Box(20, 120, 180, 180), "WAIT!", "Стой!"),
        )
        val plans = PageLettering.plan(pixels, width, height, regions, "ru", { _, _ -> measurer })
        plans.map { it.region.text } shouldBe listOf("WAIT!")
    }

    @Test
    fun `a word a little too wide is set smaller, a long one in a narrow bubble is cut once`() {
        // A little smaller whole rather than cut
        val little = bubbleWith("ВСЁ ЗАМЕЧАТЕЛЬНО")
        little.block.lines.map { it.text.trim() } shouldBe listOf("ВСЁ", "ЗАМЕЧАТЕЛЬНО")
        // Whole, the long word would leave the text far smaller (17 characters across a bubble
        // of 12 at its size): cut once between syllables, never bigger than the original
        val long = bubbleWith("ВСЁ ПЕРЕПРАВИТЕЛЬСТВА")
        long.block.lines.map { it.text.trim() } shouldBe listOf("ВСЁ", "ПЕРЕПРАВИ-", "ТЕЛЬСТВА")
        (long.block.size > little.block.size) shouldBe true
        (long.block.size <= 20 / measurer.capHeight + 1) shouldBe true
        // Marks stay with their word: no line starts with a dash or is only "…"
        val marks = bubbleWith("…ПОБЫТЬ ОДНОМУ — НЕПЛОХО.")
        marks.block.lines.none { it.text.trim().startsWith("—") || it.text.trim() == "…" } shouldBe true
        pieces("…ПОБЫТЬ ОДНОМУ — НЕПЛОХО.").pieces shouldBe listOf("…ПОБЫТЬ ", "ОДНОМУ — ", "НЕПЛОХО.")
        pieces("— ДА!").pieces shouldBe listOf("— ДА!")
        pieces("...ВИТАЕШЬ В ОБЛАКАХ?").pieces.first() shouldBe "...ВИТАЕШЬ "
        // A text of one word is made smaller, not cut, while it fits at a normal size
        bubbleWith("ПРЕДУСМОТРИТЕЛЬНО").block.lines.size shouldBe 1
    }

    /** [text] set in a closed round bubble (240 × 160) where "IT'S OKAY." was, letters 20 pixels high. */
    private fun bubbleWith(text: String): PageLettering.Lettering {
        val width = 400
        val height = 300
        val pixels = IntArray(width * height) { -0x1 }
        // A closed round bubble with short lines of letters: "ALL GOOD." in it
        for (y in 0 until height) {
            for (x in 0 until width) {
                val dx = (x - 200) / 120.0
                val dy = (y - 150) / 80.0
                val r = dx * dx + dy * dy
                if (r in 1.0..1.06) pixels[y * width + x] = -0x1000000
            }
        }
        for (x in 150 until 250 step 7) for (y in 140 until 160) pixels[y * width + x] = -0x1000000
        val region = TextRegion(RegionType.Bubble, Box(140, 130, 260, 170), "IT'S OKAY.", text)
        return PageLettering.plan(pixels, width, height, listOf(region), "ru", { _, _ -> measurer }).single()
    }
}
