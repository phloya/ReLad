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
}
