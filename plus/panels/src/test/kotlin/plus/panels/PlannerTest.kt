package plus.panels

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import plus.panels.StripPlanner.Band
import plus.panels.StripPlanner.Item

class PanelPlannerTest {

    // A phone in portrait: 1080 × 2340, a 1000 × 1500 page fits at 1.08
    private val view = PanelPlanner.Viewport(1080f, 2340f, minScale = 1.08f, maxScale = 5.4f)

    @Test
    fun `zooms each panel to fill the screen`() {
        val stops = PanelPlanner.plan(listOf(Box(0.1f, 0.1f, 0.5f, 0.4f)), 1000f, 1500f, view, wholePageFirst = false)
        stops shouldHaveSize 1
        val stop = stops[0]
        stop.centerX shouldBe (300f plusOrMinus 1f)
        stop.centerY shouldBe (375f plusOrMinus 1f)
        // 400 px of panel plus a small margin across 1080 screen pixels
        stop.scale shouldBe (1080f / (400f + 36f) plusOrMinus 0.01f)
    }

    @Test
    fun `small panels are not zoomed in without limit`() {
        val stop = PanelPlanner.plan(listOf(Box(0.45f, 0.45f, 0.5f, 0.5f)), 1000f, 1500f, view, false).single()
        stop.scale shouldBe (1.08f * PanelPlanner.MAX_ZOOM plusOrMinus 0.01f)
    }

    @Test
    fun `a whole-page panel is the page view, and whole page first adds it once`() {
        val stops = PanelPlanner.plan(listOf(Box.FULL), 1000f, 1500f, view, wholePageFirst = true)
        stops shouldHaveSize 1
        stops[0].scale shouldBe 1.08f
        stops[0].focus.shouldBeNull()

        val withPanels = PanelPlanner.plan(
            listOf(Box(0f, 0f, 1f, 0.5f), Box(0f, 0.5f, 1f, 1f)),
            1000f,
            1500f,
            view,
            wholePageFirst = true,
        )
        withPanels shouldHaveSize 3
        withPanels[0].focus.shouldBeNull()
    }

    @Test
    fun `a webtoon page is shown at screen width in overlapping steps, top to bottom`() {
        // 800 × 8000 strip on the same phone: at width 1.35, 10800 screen px tall, ~5 screens
        val strip = PanelPlanner.Viewport(1080f, 2340f, minScale = 2340f / 8000f, maxScale = 5f)
        val stops = PanelPlanner.plan(listOf(Box.FULL), 800f, 8000f, strip, wholePageFirst = false)
        val scale = 1080f / 800f
        stops.forEach { it.scale shouldBe (scale plusOrMinus 0.01f) }
        val screen = 2340f / scale
        stops.first().centerY shouldBe (screen / 2 plusOrMinus 1f)
        stops.last().centerY shouldBe (8000f - screen / 2 plusOrMinus 1f)
        stops.zipWithNext().forEach { (a, b) ->
            val step = b.centerY - a.centerY
            (step > 0f && step <= screen * (1 - PanelPlanner.OVERLAP) + 1f) shouldBe true
        }
        stops shouldHaveSize 6
    }
}

class StripPlannerTest {

    private val screen = 2000f

    @Test
    fun `next brings the panel cut by the bottom edge to the top`() {
        // A panel fully visible, then one cut by the bottom edge
        val items = listOf(Item(0f, 6000f, listOf(Band(0f, 100f), Band(800f, 1500f), Band(2600f, 2800f))))
        // Pieces: 100–800, 1500–2600: the second is cut at 2000, so it goes to the top (with a margin)
        StripPlanner.next(items, screen) shouldBe (1500f - 60f plusOrMinus 0.5f)
    }

    @Test
    fun `next skips empty space below the screen`() {
        val items = listOf(Item(0f, 9000f, listOf(Band(1900f, 5000f))))
        StripPlanner.next(items, screen) shouldBe (5000f - 60f plusOrMinus 0.5f)
    }

    @Test
    fun `next continues through a piece taller than the screen`() {
        val items = listOf(Item(-500f, 9000f, listOf(Band(8000f, 8200f))))
        StripPlanner.next(items, screen) shouldBe (screen * (1 - PanelPlanner.OVERLAP) plusOrMinus 0.5f)
    }

    @Test
    fun `pieces that continue on the next page are not cut at the page boundary`() {
        // The panel runs from 1200 on the first page into the second page, which starts at 1800
        val items = listOf(
            Item(-1000f, 1800f, listOf(Band(1000f, 1200f))),
            Item(1800f, 5000f, listOf(Band(2600f, 3000f))),
        )
        StripPlanner.pieces(items) shouldBe listOf(Band(-1000f, 1000f), Band(1200f, 2600f), Band(3000f, 5000f))
        StripPlanner.next(items, screen) shouldBe (1200f - 60f plusOrMinus 0.5f)
    }

    @Test
    fun `previous shows the piece above from its top`() {
        val items = listOf(Item(-3000f, 3000f, listOf(Band(-2900f, -2800f), Band(-1500f, 100f), Band(1500f, 1700f))))
        // Above the screen: the piece -2800 to -1500, which fits on the screen
        StripPlanner.previous(items, screen) shouldBe (-2800f - 60f plusOrMinus 0.5f)

        // The top edge cuts a piece: first show that piece whole
        val cut = listOf(Item(-3000f, 3000f, listOf(Band(-2900f, -2800f), Band(-1500f, -200f), Band(1500f, 1700f))))
        StripPlanner.previous(cut, screen) shouldBe (-200f - 60f plusOrMinus 0.5f)
    }

    @Test
    fun `previous goes up a screen inside a tall piece, but not past its top`() {
        val items = listOf(Item(-6000f, 3000f, listOf(Band(-700f, -500f), Band(2500f, 2600f))))
        StripPlanner.previous(items, screen) shouldBe (-500f - 60f plusOrMinus 0.5f)
        val tall = listOf(Item(-6000f, 3000f, listOf(Band(-5000f, -4900f), Band(2500f, 2600f))))
        StripPlanner.previous(tall, screen) shouldBe (-screen * (1 - PanelPlanner.OVERLAP) plusOrMinus 0.5f)
    }

    @Test
    fun `gives up when not enough of the strip is laid out`() {
        StripPlanner.next(listOf(Item(0f, 1500f, emptyList())), screen).shouldBeNull()
        StripPlanner.previous(listOf(Item(10f, 3000f, emptyList())), screen).shouldBeNull()
    }
}
