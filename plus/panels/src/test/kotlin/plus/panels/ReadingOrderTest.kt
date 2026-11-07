package plus.panels

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReadingOrderTest {

    private fun box(l: Float, t: Float, r: Float, b: Float) = Box(l, t, r, b)

    @Test
    fun `a spread is read page by page, the right page first for manga`() {
        // Each half has two tiers; reading tier by tier across both pages would be wrong
        val rightTop = box(0.52f, 0.05f, 0.97f, 0.45f)
        val rightBottom = box(0.52f, 0.5f, 0.97f, 0.95f)
        val leftTop = box(0.03f, 0.05f, 0.48f, 0.45f)
        val leftBottom = box(0.03f, 0.5f, 0.48f, 0.95f)
        val panels = listOf(leftTop, rightBottom, leftBottom, rightTop)

        ReadingOrder.sort(panels, rightToLeft = true, pageAspect = 1.4f) shouldBe
            listOf(rightTop, rightBottom, leftTop, leftBottom)
        ReadingOrder.sort(panels, rightToLeft = false, pageAspect = 1.4f) shouldBe
            listOf(leftTop, leftBottom, rightTop, rightBottom)
    }

    @Test
    fun `a panel across the middle of a spread is read with its tier`() {
        val wide = box(0.03f, 0.05f, 0.97f, 0.45f)
        val right = box(0.52f, 0.5f, 0.97f, 0.95f)
        val left = box(0.03f, 0.5f, 0.48f, 0.95f)
        ReadingOrder.sort(listOf(left, right, wide), rightToLeft = true, pageAspect = 1.4f) shouldBe
            listOf(wide, right, left)
    }

    @Test
    fun `slightly overlapping slanted panels are still in tiers`() {
        val upperRight = box(0.5f, 0.05f, 0.95f, 0.42f)
        val upperLeft = box(0.05f, 0.05f, 0.52f, 0.5f)
        val lower = box(0.05f, 0.4f, 0.95f, 0.95f)
        ReadingOrder.sort(listOf(lower, upperLeft, upperRight), rightToLeft = true, pageAspect = 0.7f) shouldBe
            listOf(upperRight, upperLeft, lower)
    }

    @Test
    fun `interlocking panels fall back to top first, then by direction`() {
        // A pinwheel: no straight line separates any group
        val a = box(0.05f, 0.05f, 0.65f, 0.35f)
        val b = box(0.7f, 0.05f, 0.95f, 0.65f)
        val c = box(0.35f, 0.7f, 0.95f, 0.95f)
        val d = box(0.05f, 0.4f, 0.3f, 0.95f)
        val e = box(0.35f, 0.4f, 0.65f, 0.65f)
        ReadingOrder.sort(listOf(a, b, c, d, e), rightToLeft = true, pageAspect = 0.7f) shouldBe
            listOf(b, a, e, d, c)
    }
}
