package plus.translate

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StripPartsTest {

    @Test
    fun `an ordinary page is one part`() {
        StripParts.parts(1000, 1500, IntArray(1500) { 50 }, 1.0) shouldBe listOf(0 until 1500)
    }

    @Test
    fun `a strip is cut in the gaps between panels, as low as a part allows`() {
        // 100 wide, 1000 tall: parts of at most 200 rows; gaps at rows 150–159, 340–349 and 520–529
        val spread = IntArray(1000) { 50 }
        for (gap in listOf(150, 340, 520)) for (y in gap until gap + 10) spread[y] = 0
        val parts = StripParts.parts(100, 1000, spread, 1.0)
        parts.first() shouldBe (0 until 154)
        parts[1] shouldBe (154 until 344)
        parts[2] shouldBe (344 until 524)
        // Every row is in a part; parts touch, or overlap where there was no gap
        parts.last().last shouldBe 999
        parts.zipWithNext().all { (a, b) -> b.first <= a.last + 1 } shouldBe true
        parts.all { it.last + 1 - it.first <= 200 } shouldBe true
    }

    @Test
    fun `without a gap the next part starts higher, so a bubble on the cut is whole in one`() {
        val parts = StripParts.parts(100, 600, IntArray(600) { 50 }, 1.0)
        parts[0] shouldBe (0 until 200)
        parts[1].first shouldBe 160
    }

    @Test
    fun `gaps are found on a smaller copy of the page`() {
        // The copy has a quarter of the rows: its row 40 is page rows 160–163
        val spread = IntArray(250) { 50 }
        spread[40] = 0
        spread[41] = 0
        StripParts.parts(100, 1000, spread, 4.0).first() shouldBe (0 until 160)
    }

    @Test
    fun `text seen in two overlapping parts is kept once`() {
        val regions = listOf(
            TextRegion(RegionType.Bubble, Box(10, 150, 90, 195), "WHERE ARE YOU GOING", "Куда ты"),
            TextRegion(RegionType.Bubble, Box(12, 160, 88, 196), "WHERE ARE YOU GOING?", "Куда ты?"),
            TextRegion(RegionType.Bubble, Box(10, 300, 90, 340), "HEY", "Эй"),
        )
        StripParts.withoutRepeats(regions).map { it.translation } shouldBe listOf("Куда ты", "Эй")
    }

    @Test
    fun `boxes from parts come back in page rows`() {
        val answer = """
            {"regions":[
              {"part":1,"type":"bubble","box":[10,20,90,60],"text":"A","translation":"А"},
              {"part":2,"type":"bubble","box":[5,10,45,30],"text":"B","translation":"Б"}
            ],"summary":"…"}
        """.trimIndent()
        val parts = listOf(
            TranslationParser.ImagePart(top = 0, rows = 200, imageWidth = 100, imageHeight = 200),
            // The second part was scaled to half for the model
            TranslationParser.ImagePart(top = 200, rows = 200, imageWidth = 50, imageHeight = 100),
        )
        val regions = TranslationParser.parseParts(answer, 100, BoxUnits.Pixels, parts).regions
        regions[0].box shouldBe Box(10, 20, 90, 60)
        regions[1].box shouldBe Box(10, 220, 90, 260)
    }
}
