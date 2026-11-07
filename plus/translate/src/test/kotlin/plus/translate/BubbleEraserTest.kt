package plus.translate

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
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
}
