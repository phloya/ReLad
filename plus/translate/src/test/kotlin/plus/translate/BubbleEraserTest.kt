package plus.translate

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class BubbleEraserTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val gray = 0xFF808080.toInt()

    /** A gray page with a white elliptical bubble, a black outline and black "text" strokes inside. */
    private class Page(val width: Int = 200, val height: Int = 200) {
        val pixels = IntArray(width * height) { 0xFF808080.toInt() }

        fun inEllipse(x: Int, y: Int, cx: Int, cy: Int, rx: Int, ry: Int): Double {
            val dx = (x - cx).toDouble() / rx
            val dy = (y - cy).toDouble() / ry
            return dx * dx + dy * dy
        }

        fun bubble(cx: Int, cy: Int, rx: Int, ry: Int) {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val d = inEllipse(x, y, cx, cy, rx, ry)
                    when {
                        d <= 0.9 -> set(x, y, 0xFFFFFFFF.toInt())
                        d <= 1.1 -> set(x, y, 0xFF000000.toInt())
                    }
                }
            }
        }

        fun rect(left: Int, top: Int, right: Int, bottom: Int, color: Int) {
            for (y in top until bottom) for (x in left until right) set(x, y, color)
        }

        operator fun get(x: Int, y: Int) = pixels[y * width + x]
        fun set(x: Int, y: Int, color: Int) {
            pixels[y * width + x] = color
        }
    }

    @Test
    fun `erases text strokes but keeps the outline and the art`() {
        val page = Page()
        page.bubble(cx = 100, cy = 100, rx = 70, ry = 45)
        // "Text": two black strokes inside the bubble
        page.rect(80, 90, 85, 110, black)
        page.rect(110, 90, 115, 110, black)

        val result = BubbleEraser.erase(
            page.pixels,
            page.width,
            page.height,
            textBox = Box(75, 85, 120, 115),
            searchBox = Box(0, 0, 200, 200),
        )

        page[82, 100] shouldBe white
        page[112, 100] shouldBe white
        // Outline on the left and right of the bubble, gray art outside
        page[30, 100] shouldBe black
        page[170, 100] shouldBe black
        page[5, 5] shouldBe gray
        val erased = result.erased.shouldNotBeNull()
        (erased.left < 75) shouldBe true
        (erased.right > 120) shouldBe true
        result.background shouldBe white
        result.enclosed shouldBe true
    }

    @Test
    fun `does not paint far from the text on a white page`() {
        val page = Page()
        page.rect(0, 0, 200, 200, white)
        // Art line well away from the text
        page.rect(20, 0, 22, 200, black)
        page.rect(100, 100, 104, 110, black)

        val result = BubbleEraser.erase(
            page.pixels,
            page.width,
            page.height,
            textBox = Box(95, 95, 110, 115),
            searchBox = Box(0, 0, 200, 200),
        )

        page[102, 105] shouldBe white
        page[21, 100] shouldBe black
        result.enclosed shouldBe false
    }

    @Test
    fun `nothing light in the box means nothing to erase`() {
        val page = Page()

        val result = BubbleEraser.erase(page.pixels, page.width, page.height, Box(10, 10, 50, 50), Box(0, 0, 100, 100))

        result.erased.shouldBeNull()
        page[20, 20] shouldBe gray
    }

    @Test
    fun `a closed bubble is erased whole, also a line the model's box missed`() {
        val page = Page()
        page.bubble(cx = 100, cy = 100, rx = 70, ry = 45)
        page.rect(80, 70, 120, 74, black) // first line, above the model's box
        page.rect(80, 95, 120, 99, black)

        val result = BubbleEraser.erase(
            page.pixels,
            page.width,
            page.height,
            Box(78, 90, 122, 104),
            Box(0, 0, 200, 200),
        )

        page[100, 72] shouldBe white
        page[100, 97] shouldBe white
        page[100, 55] shouldBe black // the outline stays
        result.rows.shouldNotBeNull()
    }

    @Test
    fun `an open bubble does not spill white onto the art beside it`() {
        val page = Page()
        page.rect(0, 0, 200, 200, 0xFFE0E0E0.toInt()) // light gray art, light enough to be filled
        page.bubble(cx = 120, cy = 100, rx = 60, ry = 40)
        page.rect(55, 90, 75, 110, white) // the outline is open on the left
        page.rect(100, 95, 140, 99, black)

        val result = BubbleEraser.erase(
            page.pixels,
            page.width,
            page.height,
            Box(60, 85, 150, 110),
            Box(0, 0, 200, 200),
        )

        page[120, 97] shouldNotBe black
        page[20, 100] shouldBe 0xFFE0E0E0.toInt()
        result.rows.shouldBeNull()
    }

    @Test
    fun `two joined bubbles keep the line between them and each its own text`() {
        val page = Page(width = 240)
        page.bubble(cx = 80, cy = 100, rx = 60, ry = 45)
        page.bubble(cx = 160, cy = 100, rx = 60, ry = 45)
        // Where they overlap, the second one's outline arc stays: it divides the two
        for (y in 0 until 200) {
            for (x in 0 until 240) {
                val d = page.inEllipse(x, y, 160, 100, 60, 45)
                if (d > 0.9 && d <= 1.1 && x < 120) page.set(x, y, black)
            }
        }
        page.rect(60, 95, 90, 99, black) // left text
        page.rect(165, 95, 195, 99, black) // right text

        val result = BubbleEraser.erase(page.pixels, page.width, page.height, Box(55, 90, 95, 104), Box(0, 0, 240, 200))

        page[75, 97] shouldBe white
        page[180, 97] shouldBe black // the other bubble's text is not this one's to erase
        page[101, 100] shouldBe black // the dividing line stays
        val room = result.room.shouldNotBeNull()
        (room.between(97f, 98f)!!.second < 105f) shouldBe true
    }

    @Test
    fun `an open bubble keeps its outline and the tone around it, its letters close to the outline`() {
        val page = Page(width = 300, height = 260)
        page.rect(0, 0, 300, 260, white)
        // A tone of dots on the white page, then a round bubble over it
        for (y in 60 until 200 step 6) for (x in 4 until 80 step 6) page.rect(x, y, x + 2, y + 2, black)
        for (y in 0 until 260) {
            for (x in 0 until 300) {
                val r = kotlin.math.hypot(x - 150.0, y - 130.0)
                if (r <= 57) {
                    page.set(x, y, white)
                } else if (r <= 60) {
                    page.set(x, y, black)
                }
            }
        }
        // Its outline is open at the bottom (a caption was written over it): the white page and the
        // bubble are one light area, and the outline is an island in it
        page.rect(130, 180, 170, 200, white)
        // A line of letters running up to the outline on both sides, and "..." after the line
        for (x in 98 until 204 step 10) page.rect(x, 120, x + 5, 132, black)
        for (x in 120 until 140 step 6) page.rect(x, 140, x + 2, 142, black)

        val result = BubbleEraser.erase(
            page.pixels,
            page.width,
            page.height,
            Box(105, 115, 195, 137),
            Box(0, 0, 300, 260),
        )

        page[110, 125] shouldBe white
        page[200, 125] shouldBe white
        page[132, 141] shouldBe white
        // The outline (left, right, top) and the tone stay
        page[91, 130] shouldBe black
        page[208, 130] shouldBe black
        page[150, 71] shouldBe black
        page[76, 126] shouldBe black
        result.enclosed shouldBe false
    }

    @Test
    fun `a bubble whose edge is a ring of strokes is erased whole, its strokes and the art beyond kept`() {
        val page = Page(width = 300, height = 260)
        page.rect(0, 0, 300, 260, white)
        page.rect(10, 100, 40, 160, black) // art beyond the bubble
        // The bubble's edge: short strokes outwards, light between them
        for (k in 0 until 120) {
            val angle = Math.toRadians(k * 3.0)
            var r = 60.0
            while (r <= 80) {
                val x = (150 + r * kotlin.math.cos(angle)).toInt()
                val y = (130 + r * kotlin.math.sin(angle)).toInt()
                page.rect(x, y, x + 2, y + 2, black)
                r += 0.5
            }
        }
        // Letters up to near the edge
        for (x in 98 until 204 step 10) page.rect(x, 120, x + 5, 132, black)

        val result = BubbleEraser.erase(
            page.pixels,
            page.width,
            page.height,
            Box(105, 115, 195, 137),
            Box(0, 0, 300, 260),
        )

        page[110, 125] shouldBe white
        page[200, 125] shouldBe white
        page[220, 130] shouldBe black
        page[150, 60] shouldBe black
        page[20, 130] shouldBe black
        result.rows.shouldNotBeNull()
    }

    @Test
    fun `an open bubble's line is erased as far as it goes, past the model's box`() {
        val page = Page()
        page.rect(0, 0, 200, 200, white)
        page.rect(0, 150, 200, 200, gray) // art below, connected to the page's white
        // One line of "letters" from x=40 to x=150; the model's box covers only its start
        for (x in 40 until 150 step 8) page.rect(x, 50, x + 4, 60, black)
        page.rect(40, 65, 44, 75, black) // the next line, below the box

        BubbleEraser.erase(page.pixels, page.width, page.height, Box(38, 48, 80, 62), Box(0, 0, 200, 200))

        page[144, 55] shouldBe white
        page[42, 70] shouldBe white
        page[100, 170] shouldBe gray
    }

    @Test
    fun `a bubble cut by the page edge counts as closed`() {
        val page = Page()
        page.bubble(cx = 20, cy = 100, rx = 70, ry = 45) // its left part is off the page
        page.rect(20, 95, 60, 99, black)

        val result = BubbleEraser.erase(page.pixels, page.width, page.height, Box(15, 90, 65, 104), Box(0, 0, 200, 200))

        result.enclosed shouldBe true
        result.rows.shouldNotBeNull()
        page[40, 97] shouldBe white
    }

    @Test
    fun `light bits of the art at the box's edge are not taken for the bubble`() {
        val page = Page()
        page.bubble(cx = 100, cy = 100, rx = 50, ry = 35)
        page.rect(85, 95, 115, 99, black)
        // A white bit of art just left of the bubble, inside the model's too-wide box
        page.rect(30, 92, 46, 106, white)

        val result = BubbleEraser.erase(
            page.pixels,
            page.width,
            page.height,
            Box(30, 88, 130, 110),
            Box(0, 0, 200, 200),
        )

        val erased = result.erased.shouldNotBeNull()
        (erased.left > 46) shouldBe true
        page[38, 100] shouldBe white
        page[20, 100] shouldBe gray
    }

    @Test
    fun `light letters on dark art are left for the art cleaner`() {
        val page = Page()
        page.rect(0, 0, 200, 200, 0xFF303030.toInt())
        page.rect(60, 90, 64, 110, white)
        page.rect(80, 90, 84, 110, white)

        val result = BubbleEraser.erase(page.pixels, page.width, page.height, Box(55, 85, 90, 115), Box(0, 0, 200, 200))

        result.onArt shouldBe true
        page[62, 100] shouldBe white
    }

    @Test
    fun `a small bubble among dark art is still a bubble`() {
        val page = Page()
        page.rect(0, 0, 200, 200, 0xFF303030.toInt())
        // A narrow white shout with dots and "!!" in it; the model's box is much wider than it
        page.rect(90, 60, 110, 140, white)
        for (y in listOf(70, 78, 86)) page.rect(98, y, 102, y + 4, black)
        page.rect(95, 100, 98, 120, black)
        page.rect(102, 100, 105, 120, black)

        val result = BubbleEraser.erase(
            page.pixels,
            page.width,
            page.height,
            Box(60, 55, 140, 145),
            Box(0, 0, 200, 200),
        )

        result.onArt shouldBe false
        page[100, 80] shouldBe white
        page[96, 110] shouldBe white
        page[50, 100] shouldBe 0xFF303030.toInt()
    }
}
