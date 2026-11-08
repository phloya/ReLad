package plus.translate

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage

/** Letters on backgrounds of any colour, drawn with Java2D: found, measured and erased to their background. */
class PlainLettersTest {

    private class Page(val width: Int = 400, val height: Int = 300) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        }

        fun fill(color: Color, box: Box = Box(0, 0, width, height)) {
            g.color = color
            g.fillRect(box.left, box.top, box.width, box.height)
        }

        /** [text] with its baseline at [y], from [x]; [slant] leans it right; [outline] around it. */
        fun text(
            text: String,
            x: Int,
            y: Int,
            color: Color,
            size: Float = 30f,
            slant: Double = 0.0,
            outline: Color? = null,
            glow: Color? = null,
        ) {
            val font = Font(Font.SANS_SERIF, Font.BOLD, 1).deriveFont(
                AffineTransform(
                    size.toDouble(),
                    0.0,
                    -slant * size,
                    size.toDouble(),
                    0.0,
                    0.0,
                ),
            )
            val shape = font.createGlyphVector(g.fontRenderContext, text).getOutline(x.toFloat(), y.toFloat())
            glow?.let {
                // A soft glow: wide faint strokes under narrower ones
                for (k in 4 downTo 1) {
                    g.color = Color(it.red, it.green, it.blue, 70)
                    g.stroke = BasicStroke(4f * k, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g.draw(shape)
                }
            }
            outline?.let {
                g.color = it
                g.stroke = BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(shape)
            }
            g.color = color
            g.fill(shape)
        }

        val pixels: IntArray get() = image.getRGB(0, 0, width, height, null, 0, width)
    }

    private fun close(a: Int, b: Color, within: Float) = PlainLetters.distance(a, b.rgb) <= within

    @Test
    fun `letters on a coloured window are found with their colours and outline`() {
        val page = Page()
        val blue = Color(0x3B, 0xB4, 0xFC)
        page.fill(blue)
        page.text("SYSTEM ERROR", 60, 160, Color.WHITE, outline = Color(0x10, 0x20, 0x60))
        val letters = PlainLetters.find(page.pixels, page.width, page.height, Box(50, 120, 330, 175)).shouldNotBeNull()

        close(letters.background, blue, 10f) shouldBe true
        close(letters.fill, Color.WHITE, 40f) shouldBe true
        close(letters.edge.shouldNotBeNull(), Color(0x10, 0x20, 0x60), 60f) shouldBe true
        letters.flat shouldBe true
        (letters.whole >= 0.9f) shouldBe true
        // As high as the capitals drawn (about 0.72 of the font size)
        (letters.letterHeight in 18f..26f) shouldBe true
        letters.style.slant shouldBe 0f
    }

    @Test
    fun `italic letters lean and upright ones do not`() {
        val italic = Page()
        italic.fill(Color.WHITE)
        italic.text("THE QUICK BROWN FOX", 20, 120, Color.BLACK, slant = 0.25)
        val leaning = PlainLetters.find(italic.pixels, italic.width, italic.height, Box(15, 90, 385, 130))
            .shouldNotBeNull()
        (leaning.style.slant in 0.15f..0.35f) shouldBe true

        val upright = Page()
        upright.fill(Color.WHITE)
        upright.text("THE QUICK BROWN FOX", 20, 120, Color.BLACK)
        PlainLetters.find(upright.pixels, upright.width, upright.height, Box(15, 90, 385, 130))
            .shouldNotBeNull().style.slant shouldBe 0f
    }

    @Test
    fun `a gradient behind the letters is filled back smoothly`() {
        val page = Page()
        val top = Color(40, 60, 160)
        val bottom = Color(120, 180, 250)
        page.g.paint = GradientPaint(0f, 0f, top, 0f, page.height.toFloat(), bottom)
        page.g.fillRect(0, 0, page.width, page.height)
        val clean = page.pixels
        page.text("HELLO THERE", 60, 160, Color.BLACK)
        val pixels = page.pixels
        val letters = PlainLetters.find(pixels, page.width, page.height, Box(50, 125, 300, 170)).shouldNotBeNull()
        letters.flat shouldBe false
        letters.smooth shouldBe true

        SmoothFill.fill(pixels, page.width, letters.area, letters.erase, letters.backgroundMask)
        var worst = 0f
        for (i in pixels.indices) worst = maxOf(worst, PlainLetters.distance(pixels[i], clean[i]))
        (worst < 15f) shouldBe true
    }

    @Test
    fun `a glow around the letters is erased with them and kept as a glow`() {
        val page = Page()
        page.fill(Color.BLACK)
        page.text("NOTHING", 80, 170, Color.WHITE, size = 40f, glow = Color(200, 200, 200))
        val pixels = page.pixels
        val letters = PlainLetters.find(pixels, page.width, page.height, Box(70, 125, 290, 180)).shouldNotBeNull()
        letters.glow.shouldNotBeNull()
        (letters.glowWidth > 2f) shouldBe true

        // Painted with the background, nothing of the letters or their glow is left (only a trace
        // too faint to see)
        for (i in letters.erase.indices) {
            if (letters.erase[i]) {
                pixels[
                    (letters.area.top + i / letters.area.width) * page.width + letters.area.left +
                        i % letters.area.width,
                ] =
                    letters.background
            }
        }
        (pixels.maxOf { BubbleEraser.luminance(it) } <= 12) shouldBe true
    }

    @Test
    fun `an icon on the same screen and another text are not taken for the text`() {
        val page = Page()
        val teal = Color(0x29, 0x73, 0x6D)
        page.fill(teal)
        // An icon above the text, further than a line away
        page.g.color = Color.WHITE
        page.g.fillOval(150, 40, 30, 30)
        page.text("THREE WAYS", 90, 150, Color.WHITE)
        // Another text beside it with its own box
        page.text("EDIT", 300, 150, Color.WHITE)
        val letters = PlainLetters.find(
            page.pixels,
            page.width,
            page.height,
            Box(85, 120, 270, 160),
            others = listOf(Box(295, 120, 380, 160)),
        )
            .shouldNotBeNull()

        (letters.box.top > 75) shouldBe true
        (letters.box.right < 295) shouldBe true
    }

    @Test
    fun `texture or soft shades without letters are not letters`() {
        val page = Page()
        page.g.paint = GradientPaint(0f, 0f, Color(30, 30, 30), 400f, 0f, Color(90, 90, 90))
        page.g.fillRect(0, 0, page.width, page.height)
        PlainLetters.find(page.pixels, page.width, page.height, Box(100, 100, 300, 160)).shouldBeNull()
    }

    @Test
    fun `letters on a black caption box are erased to black and set in their colour`() {
        val page = Page()
        page.fill(Color.WHITE)
        page.fill(Color.BLACK, Box(40, 60, 360, 240))
        page.text("WHAT IF I", 90, 130, Color(250, 250, 250))
        page.text("WAS ABLE", 90, 175, Color(250, 250, 250))
        val pixels = page.pixels
        val measurer = object : TextMeasurer {
            override fun width(text: String) = text.length * 0.6f
            override val capHeight = 0.7f
            override val xHeight = 0.5f
            override val lineHeight = 1.3f
        }
        val region = TextRegion(RegionType.Caption, Box(80, 95, 280, 185), "WHAT IF I WAS ABLE", "А ЕСЛИ БЫ Я СМОГ")
        val plan = PageLettering.plan(pixels, page.width, page.height, listOf(region), "ru", { _, _ -> measurer })
            .single()

        // The original letters are gone, painted black (not white)
        for (y in 100 until 180) {
            for (x in 85 until 280) (BubbleEraser.luminance(pixels[y * page.width + x]) < 20) shouldBe true
        }
        (BubbleEraser.luminance(plan.fill) > 200) shouldBe true
        plan.skew shouldBe 0f
    }

    private val measurer = object : TextMeasurer {
        override fun width(text: String) = text.length * 0.6f
        override val capHeight = 0.7f
        override val xHeight = 0.5f
        override val lineHeight = 1.3f
    }

    @Test
    fun `a small closed bubble is erased though the model's box sticks out of it`() {
        val page = Page()
        // Hatched art all round a small white bubble with an outline
        page.fill(Color(200, 200, 200))
        page.g.color = Color(40, 40, 40)
        page.g.stroke = BasicStroke(2f)
        for (k in -page.height until page.width step 6) page.g.drawLine(k, 0, k + page.height, page.height)
        page.g.color = Color.WHITE
        page.g.fillOval(150, 100, 100, 80)
        page.g.color = Color.BLACK
        page.g.drawOval(150, 100, 100, 80)
        page.text("HEY! THAT'S", 168, 135, Color.BLACK, size = 14f)
        page.text("KIDNAPPING!", 166, 156, Color.BLACK, size = 14f)
        val pixels = page.pixels
        // Half of the box is out of the bubble, on the art
        val region =
            TextRegion(RegionType.Bubble, Box(195, 105, 300, 175), "HEY! THAT'S KIDNAPPING!", "ЭЙ! ЭТО ЖЕ ПОХИЩЕНИЕ!")
        val plan = PageLettering.plan(pixels, page.width, page.height, listOf(region), "ru", { _, _ -> measurer })
            .single()

        // The original letters are gone: the bubble is white inside
        for (y in 118 until 162) {
            for (x in 166 until 236) (BubbleEraser.luminance(pixels[y * page.width + x]) > 200) shouldBe true
        }
        // and the translation is set in it, not on the art beside it
        for (line in plan.block.lines) (line.centerX in 160f..240f) shouldBe true
    }

    @Test
    fun `a sign on a shaded light background is not painted over with one colour`() {
        val page = Page()
        // A colour page's light sky, and a figure's pink hat the letters run on onto
        page.g.paint = GradientPaint(0f, 0f, Color(190, 240, 160), 250f, 0f, Color(255, 190, 230))
        page.g.fillRect(0, 0, page.width, page.height)
        page.fill(Color(230, 90, 150), Box(200, 100, 400, 200))
        val clean = page.pixels
        page.text("TV ANIME BERSERK", 40, 160, Color(220, 30, 70), size = 36f, outline = Color.WHITE)
        val pixels = page.pixels
        val region = TextRegion(RegionType.Other, Box(40, 125, 240, 170), "TVアニメ", "ТВ-АНИМЕ")
        PageLettering.plan(pixels, page.width, page.height, listOf(region), "ru", { _, _ -> measurer })

        // Where there were no letters the background is as it was: no patch of one colour
        val letters = BooleanArray(clean.size) { PlainLetters.distance(page.pixels[it], clean[it]) > 20f }
        for (y in 110 until 185) {
            for (x in 30 until 195) {
                var near = false
                for (dy in -6..6) for (dx in -6..6) if (letters[(y + dy) * page.width + x + dx]) near = true
                if (!near) {
                    (PlainLetters.distance(pixels[y * page.width + x], clean[y * page.width + x]) < 12f) shouldBe
                        true
                }
            }
        }
    }
}
