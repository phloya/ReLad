package plus.translate

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage

class TextPlacerTest {

    /** A page drawn with Java2D: grey hatched art, white bubbles with a black outline, lettering. */
    internal class Page(val width: Int = 600, val height: Int = 800) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        private val g = image.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            color = Color(150, 150, 150)
            fillRect(0, 0, width, height)
            // Hatching: dark lines close together, as art is drawn
            color = Color(40, 40, 40)
            stroke = BasicStroke(2f)
            var x = -height
            while (x < width) {
                drawLine(x, height, x + height, 0)
                x += 7
            }
        }
        private val size = 20f

        fun bubble(cx: Int, cy: Int, rx: Int, ry: Int) {
            val shape = Ellipse2D.Float((cx - rx).toFloat(), (cy - ry).toFloat(), 2f * rx, 2f * ry)
            g.color = Color.WHITE
            g.fill(shape)
            g.color = Color.BLACK
            g.stroke = BasicStroke(3f)
            g.draw(shape)
        }

        /** Lines of text centred on [cx], the first line's top at [top]; returns the box around their ink. */
        fun text(cx: Int, top: Int, vararg lines: String, light: Boolean = false): Box {
            var left = Int.MAX_VALUE
            var right = 0
            var bottom = 0
            val pitch = 26
            for ((i, line) in lines.withIndex()) {
                val shape = TestFonts.outline(line, size)
                val bounds = shape.bounds2D
                val x = cx - bounds.width / 2 - bounds.x
                val baseline = top + i * pitch - bounds.y
                val outline = AffineTransform.getTranslateInstance(x, baseline).createTransformedShape(shape)
                if (light) {
                    g.color = Color.BLACK
                    g.stroke = BasicStroke(4f + TestFonts.bold(size))
                    g.draw(outline)
                    g.color = Color.WHITE
                } else {
                    g.color = Color.BLACK
                }
                TestFonts.fill(g, outline, size)
                left = minOf(left, outline.bounds.x)
                right = maxOf(right, outline.bounds.x + outline.bounds.width)
                bottom = maxOf(bottom, outline.bounds.y + outline.bounds.height)
            }
            return Box(left, top, right, bottom)
        }

        val pixels: IntArray get() = image.getRGB(0, 0, width, height, null, 0, width)
    }

    private fun region(box: Box, text: String, type: RegionType = RegionType.Bubble) =
        TextRegion(type, box, text, "перевод")

    /** Nearly the same box: within a few pixels on every side. */
    private fun Box.near(other: Box, slack: Int = 8) =
        kotlin.math.abs(left - other.left) <= slack && kotlin.math.abs(top - other.top) <= slack &&
            kotlin.math.abs(right - other.right) <= slack && kotlin.math.abs(bottom - other.bottom) <= slack

    @Test
    fun `letters in bubbles are found as texts, the art is not`() {
        val page = Page()
        page.bubble(150, 150, 110, 70)
        page.bubble(430, 500, 120, 80)
        val first = page.text(150, 115, "WHERE DID", "THAT KID GO?")
        val second = page.text(430, 465, "I COULDN'T", "DO A THING", "AT ALL!!")

        val texts = TextFinder.find(page.pixels, page.width, page.height)

        texts.shouldHaveSize(2)
        texts.map { it.lines.size }.sorted() shouldBe listOf(2, 3)
        texts.any { it.box.near(first) } shouldBe true
        texts.any { it.box.near(second) } shouldBe true
    }

    @Test
    fun `text the answer left out is found, text it has is not`() {
        val page = Page()
        page.bubble(150, 150, 110, 70)
        page.bubble(430, 500, 120, 80)
        val first = page.text(150, 115, "WHERE DID", "THAT KID GO?")
        val second = page.text(430, 465, "I COULDN'T", "DO A THING", "AT ALL!!")

        // The answer has only the first bubble, its box a little off
        val answer =
            listOf(
                region(Box(first.left + 20, first.top - 10, first.right + 30, first.bottom), "WHERE DID THAT KID GO?"),
            )
        val missed = MissedText.find(page.pixels, page.width, page.height, answer)

        missed.shouldHaveSize(1)
        missed.single().box.near(second) shouldBe true
        MissedText.find(page.pixels, page.width, page.height, answer + region(second, "I COULDN'T DO A THING AT ALL!!"))
            .shouldHaveSize(0)
    }

    @Test
    fun `a box beside its bubble moves onto the bubble's letters`() {
        val page = Page()
        page.bubble(150, 150, 110, 70)
        page.bubble(430, 500, 120, 80)
        val letters = page.text(430, 465, "I COULDN'T", "DO A THING", "AT ALL!!")
        page.text(150, 115, "WHERE DID", "THAT KID GO?")
        // The model put the box a box's width to the left of the text, on the art
        val off = Box(letters.left - 150, letters.top + 20, letters.right - 150, letters.bottom + 20)

        val placed = TextPlacer.place(
            page.pixels,
            page.width,
            page.height,
            listOf(region(off, "I COULDN'T DO A THING AT ALL!!")),
        )

        placed.single().box.near(letters) shouldBe true
    }

    @Test
    fun `a box that missed lines gets the whole text, a box too big gives back its neighbour's`() {
        val page = Page()
        page.bubble(300, 200, 150, 110)
        val upper = page.text(300, 130, "HE KEPT", "GAZING UP AND", "MUMBLING")
        val lower = page.text(300, 240, "...BUT HE WAS", "OUT OF IT")
        // The first box holds only the first line; the second one also takes the first text's last line
        val regions = listOf(
            region(Box(upper.left, upper.top - 4, upper.right, upper.top + 22), "HE KEPT GAZING UP AND MUMBLING"),
            region(
                Box(lower.left - 10, upper.bottom - 20, lower.right + 10, lower.bottom + 8),
                "...BUT HE WAS OUT OF IT",
            ),
        )

        val placed = TextPlacer.place(page.pixels, page.width, page.height, regions)

        placed[0].box.near(upper) shouldBe true
        placed[1].box.near(lower) shouldBe true
    }

    @Test
    fun `a line drawn between two joined bubbles keeps their texts apart`() {
        val page = Page()
        page.bubble(200, 200, 120, 70)
        page.bubble(360, 200, 120, 70)
        // The second bubble's outline runs between the two texts, close to both
        val left = page.text(195, 180, "OKAY!")
        val right = page.text(330, 180, "GO!")

        val texts = TextFinder.find(page.pixels, page.width, page.height)

        texts.shouldHaveSize(2)
        texts.any { it.box.near(left) } shouldBe true
        texts.any { it.box.near(right) } shouldBe true
    }

    @Test
    fun `light letters with a dark edge on the art are found`() {
        val page = Page()
        val letters = page.text(300, 300, "ALL THIS", "TIME...", light = true)

        val texts = TextFinder.find(page.pixels, page.width, page.height)

        texts.filter { it.light && it.box.near(letters) }.shouldHaveSize(1)
    }

    @Test
    fun `a box with no text near it, sound effects and dots stay where the model put them`() {
        val page = Page()
        page.bubble(150, 150, 110, 70)
        page.text(150, 125, "HEY...!")
        val regions = listOf(
            region(Box(400, 600, 500, 650), "NOTHING HERE AT ALL"),
            region(Box(100, 120, 200, 150), "BAM", RegionType.Sfx),
            region(Box(20, 20, 60, 40), "……"),
        )

        TextPlacer.place(page.pixels, page.width, page.height, regions) shouldBe regions
    }

    @Test
    fun `a blank page has no text`() {
        val page = Page()
        TextFinder.find(page.pixels, page.width, page.height).shouldBeEmpty()
    }
}
