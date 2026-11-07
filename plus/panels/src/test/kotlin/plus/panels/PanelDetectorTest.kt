package plus.panels

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PanelDetectorTest {

    private val w = 700
    private val h = 1000

    /** A typical manga page: a wide panel, two side by side, a tall one beside two short ones. */
    private fun mangaPage(balloonAcrossGutter: Boolean = false, pageNumber: Boolean = false): TestPage {
        val page = TestPage(w, h)
        page.panel(30, 30, 670, 330)
        page.panel(360, 350, 670, 650)
        page.panel(30, 350, 340, 650)
        page.panel(360, 670, 670, 970)
        page.panel(30, 670, 340, 810)
        page.panel(30, 830, 340, 970)
        if (balloonAcrossGutter) page.balloon(350, 500, 60, 40)
        if (pageNumber) page.fill(340, 980, 362, 994, 0)
        page.noise(6)
        return page
    }

    private val expected = listOf(
        Box(30f, 30f, 670f, 330f), // 0: top, full width
        Box(360f, 350f, 670f, 650f), // 1: middle right
        Box(30f, 350f, 340f, 650f), // 2: middle left
        Box(360f, 670f, 670f, 970f), // 3: bottom right, tall
        Box(30f, 670f, 340f, 810f), // 4: bottom left, upper
        Box(30f, 830f, 340f, 970f), // 5: bottom left, lower
    ).map { Box(it.left / w, it.top / h, it.right / w, it.bottom / h) }

    /** Index in [expected] of each found panel, by best overlap. */
    private fun match(found: List<Box>): List<Int> = found.map { box ->
        val best = expected.indices.maxBy { overlap(box, expected[it]) }
        overlap(box, expected[best]) shouldBe (1f plusOrMinus 0.1f)
        best
    }

    private fun overlap(a: Box, b: Box) = a.intersection(b) / a.union(b).area

    @Test
    fun `finds the panels of a manga page and reads them right to left`() {
        val found = PanelDetector.findPanels(mangaPage().image())
        found shouldHaveSize 6
        match(ReadingOrder.sort(found, rightToLeft = true, pageAspect = 0.7f)) shouldBe listOf(0, 1, 2, 3, 4, 5)
        // Upright panels are their boxes
        found.forEach { it.shape.shouldBeNull() }
    }

    @Test
    fun `reads the same page left to right for comics`() {
        val found = PanelDetector.findPanels(mangaPage().image())
        match(ReadingOrder.sort(found, rightToLeft = false, pageAspect = 0.7f)) shouldBe listOf(0, 2, 1, 4, 5, 3)
    }

    @Test
    fun `a speech balloon across the gutter does not join two panels`() {
        val found = PanelDetector.findPanels(mangaPage(balloonAcrossGutter = true).image())
        found shouldHaveSize 6
        match(ReadingOrder.sort(found, rightToLeft = true, pageAspect = 0.7f)) shouldBe listOf(0, 1, 2, 3, 4, 5)
    }

    @Test
    fun `ignores the page number in the margin`() {
        PanelDetector.findPanels(mangaPage(pageNumber = true).image()) shouldHaveSize 6
    }

    @Test
    fun `finds panels on a page with black gutters`() {
        val page = TestPage(w, h, background = 8)
        page.panel(30, 30, 670, 480, ink = 255, paper = 235)
        page.panel(30, 500, 340, 970, ink = 255, paper = 235)
        page.panel(360, 500, 670, 970, ink = 255, paper = 235)
        page.noise(6)
        val found = ReadingOrder.sort(PanelDetector.findPanels(page.image()), rightToLeft = true, pageAspect = 0.7f)
        found shouldHaveSize 3
        found[1].centerX shouldBe (0.73f plusOrMinus 0.05f)
        found[2].centerX shouldBe (0.27f plusOrMinus 0.05f)
    }

    @Test
    fun `panels with a slanted gutter between them stay apart`() {
        val page = TestPage(w, h)
        // Upper panel: its bottom edge falls from y=400 on the right to y=500 on the left
        for (x in 30 until 670) {
            val bottom = 500 - 100 * (x - 30) / 640
            for (y in 30 until bottom) page.set(x, y, if (x < 34 || x > 665 || y < 34 || y > bottom - 5) 0 else 255)
            val top = bottom + 20
            for (y in top until 970) page.set(x, y, if (x < 34 || x > 665 || y < top + 4 || y > 965) 0 else 255)
        }
        page.noise(6)
        val found = ReadingOrder.sort(PanelDetector.findPanels(page.image()), rightToLeft = true, pageAspect = 0.7f)
        found shouldHaveSize 2
        found[0].top shouldBe (0.03f plusOrMinus 0.01f)
        found[1].bottom shouldBe (0.97f plusOrMinus 0.01f)
        // The corners follow the slant: the upper panel ends lower on the left than on the right
        val upper = found[0].shape.shouldNotBeNull()
        upper[7] shouldBe (0.50f plusOrMinus 0.01f) // bottom left
        upper[5] shouldBe (0.40f plusOrMinus 0.01f) // bottom right
        upper[1] shouldBe (0.03f plusOrMinus 0.01f) // top left
        val lower = found[1].shape.shouldNotBeNull()
        lower[1] shouldBe (0.52f plusOrMinus 0.01f) // top left
        lower[3] shouldBe (0.42f plusOrMinus 0.01f) // top right
    }

    /**
     * A panel that runs off the page at the top and the sides, with three balloons along its left
     * edge. Each balloon touches the page edge, a neighbour or the gutter, so the white around
     * them cuts two corners off the panel.
     */
    private fun cornersCutByBalloons(): TestPage {
        val page = TestPage(w, h)
        page.fill(0, 0, w, 380, 150)
        val random = kotlin.random.Random(5)
        repeat(120) {
            val x = random.nextInt(w)
            val y = random.nextInt(380)
            page.line(x, y, x + random.nextInt(-60, 60), (y + random.nextInt(-60, 60)).coerceIn(0, 379), 3, 0)
        }
        page.balloon(150, 90, 70, 95, halo = 10)
        page.balloon(90, 200, 110, 70, halo = 10)
        page.balloon(130, 330, 40, 55, halo = 10)
        page.panel(30, 400, 340, 970)
        page.panel(360, 400, 670, 970)
        page.noise(6)
        return page
    }

    @Test
    fun `corners cut off by balloons stay with their panel`() {
        val found = ReadingOrder.sort(PanelDetector.findPanels(cornersCutByBalloons().image()), true, 0.7f)
        found shouldHaveSize 3
        found[0].left shouldBe (0f plusOrMinus 0.01f)
        found[0].top shouldBe (0f plusOrMinus 0.01f)
        found[0].right shouldBe (1f plusOrMinus 0.01f)
        found[0].bottom shouldBe (0.38f plusOrMinus 0.01f)
    }

    @Test
    fun `a band of white art across a tall panel does not cut it`() {
        // A tall panel crossed by a big sound effect: a band of white with ragged edges that
        // breaks through the panel's borders into the gutters on both sides, like a gutter would.
        // One stroke of lettering holds the two halves of the panel together
        val page = TestPage(w, h)
        page.panel(30, 30, 330, 970)
        page.fill(350, 30, 670, 970, 0)
        page.fill(354, 34, 666, 966, 150)
        val random = kotlin.random.Random(11)
        repeat(150) {
            val x = random.nextInt(356, 664)
            val y = random.nextInt(36, 964)
            page.line(
                x,
                y,
                (x + random.nextInt(-50, 50)).coerceIn(356, 664),
                (y + random.nextInt(-50, 50)).coerceIn(36, 964),
                3,
                0,
            )
        }
        for (x in 340 until 680) {
            val top = 470 + (18 * kotlin.math.sin(x / 9.0)).toInt()
            val bottom = 540 + (18 * kotlin.math.sin(x / 7.0 + 1)).toInt()
            for (y in top until bottom) page.set(x, y, 255)
        }
        page.line(500, 440, 520, 575, 8, 0)
        page.noise(6)
        val found = ReadingOrder.sort(PanelDetector.findPanels(page.image()), rightToLeft = true, pageAspect = 0.7f)
        found shouldHaveSize 2
        found[0].top shouldBe (0.03f plusOrMinus 0.01f)
        found[0].bottom shouldBe (0.97f plusOrMinus 0.01f)
    }

    @Test
    fun `white between two figures inside a frame does not cut the panel`() {
        // Two figures in one framed panel with white between them. Hair breaking through the top
        // frame line lets the gutter into that white, which then runs from frame to frame like
        // a gutter. But the frame lines go on across it: it is inside the panel
        val page = TestPage(w, h)
        page.panel(30, 30, 670, 600)
        page.fill(34, 34, 200, 596, 120)
        page.fill(260, 34, 666, 596, 150)
        page.fill(200, 34, 260, 596, 255)
        page.fill(220, 30, 240, 34, 255)
        page.panel(30, 640, 670, 970)
        page.noise(6)
        val found = ReadingOrder.sort(PanelDetector.findPanels(page.image()), rightToLeft = true, pageAspect = 0.7f)
        found shouldHaveSize 2
        found[0].left shouldBe (0.04f plusOrMinus 0.01f)
        found[0].right shouldBe (0.96f plusOrMinus 0.01f)
    }

    @Test
    fun `panels that share a frame line without a gutter are told apart`() {
        // One frame around two panels, split only by a black line (Berserk often does this)
        val page = TestPage(w, h)
        page.fill(30, 30, 670, 970, 0)
        page.fill(34, 34, 666, 476, 200)
        page.fill(34, 480, 666, 966, 120)
        val random = kotlin.random.Random(13)
        repeat(120) {
            val top = it % 2 == 0
            val x = random.nextInt(40, 660)
            val y = if (top) random.nextInt(40, 470) else random.nextInt(486, 960)
            val y2 = if (top) {
                (
                    y + random.nextInt(
                        -40,
                        40,
                    )
                    ).coerceIn(40, 470)
            } else {
                (y + random.nextInt(-40, 40)).coerceIn(486, 960)
            }
            page.line(x, y, (x + random.nextInt(-40, 40)).coerceIn(40, 660), y2, 2, if (top) 60 else 230)
        }
        page.noise(6)
        val found = ReadingOrder.sort(PanelDetector.findPanels(page.image()), rightToLeft = true, pageAspect = 0.7f)
        found shouldHaveSize 2
        found[0].bottom shouldBe (0.48f plusOrMinus 0.01f)
        found[1].top shouldBe (0.48f plusOrMinus 0.01f)
    }

    @Test
    fun `a page drawn to the edges is one panel`() {
        // Art to the edges: short strokes and texture everywhere, no band of paper across the page
        val page = TestPage(w, h, background = 120)
        val random = kotlin.random.Random(3)
        repeat(400) {
            val x = random.nextInt(w)
            val y = random.nextInt(h)
            page.line(
                x,
                y,
                x + random.nextInt(-120, 120),
                y + random.nextInt(-120, 120),
                5,
                if (it % 2 ==
                    0
                ) {
                    0
                } else {
                    255
                },
            )
        }
        page.noise(30)
        PanelDetector.findPanels(page.image()) shouldBe listOf(Box.FULL)
    }

    @Test
    fun `a blank page is one panel`() {
        PanelDetector.findPanels(TestPage(w, h).image()) shouldBe listOf(Box.FULL)
    }

    @Test
    fun `estimates what crop borders leaves of the page`() {
        val page = TestPage(w, h)
        page.panel(70, 100, 630, 900)
        val crop = PanelDetector.analyze(page.image()).crop
        crop.left shouldBe (0.1f plusOrMinus 0.005f)
        crop.top shouldBe (0.1f plusOrMinus 0.005f)
        crop.right shouldBe (0.9f plusOrMinus 0.005f)
        crop.bottom shouldBe (0.9f plusOrMinus 0.005f)
    }

    @Test
    fun `finds the empty bands of a webtoon strip`() {
        val strip = TestPage(300, 3000)
        strip.panel(0, 100, 300, 900)
        strip.panel(20, 1200, 280, 1500)
        // A balloon floating in the gap between the second and third panel
        strip.balloon(150, 1750, 80, 50)
        strip.panel(0, 2000, 300, 2900, paper = 200)
        strip.noise(4)
        val gaps = PanelDetector.analyze(strip.image()).gaps
        val expected = listOf(0 to 100, 900 to 1200, 1500 to 1697, 1804 to 2000, 2900 to 3000)
        gaps shouldHaveSize expected.size
        // A full-width panel border is a plain line too, so a gap may start a few pixels early
        gaps.zip(expected).forEach { (gap, band) ->
            gap.start * 3000 shouldBe (band.first.toFloat() plusOrMinus 6f)
            gap.end * 3000 shouldBe (band.second.toFloat() plusOrMinus 6f)
        }
    }
}
