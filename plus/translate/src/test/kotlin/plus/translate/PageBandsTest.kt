package plus.translate

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PageBandsTest {

    private fun region(top: Int, bottom: Int, left: Int = 100, right: Int = 400) =
        TextRegion(RegionType.Bubble, Box(left, top, right, bottom), "TEXT", "ТЕКСТ")

    @Test
    fun `a page that is not a strip is one band`() {
        val bands = PageBands.of(1000, 1500, listOf(region(100, 200), region(1200, 1300)))
        bands.size shouldBe 1
        bands[0].top shouldBe 0
        bands[0].bottom shouldBe 1500
        bands[0].regions shouldBe listOf(0, 1)
    }

    @Test
    fun `a strip is drawn around its texts only, each text in one band with room around it`() {
        val regions = listOf(region(9000, 9100), region(1000, 1100), region(1300, 1400), region(14000, 14100))
        val bands = PageBands.of(800, 15000, regions)
        // The two texts close together share a band; rows with no text are in none
        bands.map { it.regions } shouldBe listOf(listOf(1, 2), listOf(0), listOf(3))
        (bands.sumOf { it.rows } < 15000 / 2) shouldBe true
        for (band in bands) {
            for (i in band.regions) {
                val box = regions[i].box
                (box.top - band.top >= 400) shouldBe true
                (band.bottom - box.bottom >= 400) shouldBe true
            }
        }
        // Boxes in a band's rows and back
        val local = PageBands.shifted(regions[0], bands[1].top)
        PageBands.shifted(local, -bands[1].top) shouldBe regions[0]
    }

    @Test
    fun `bands near each other are joined only while not too long`() {
        // Texts every 1000 rows: their surroundings (400 rows each way) don't overlap, but are close
        val regions = (0 until 12).map { region(500 + it * 1000, 600 + it * 1000, right = 250) }
        val bands = PageBands.of(800, 12500, regions)
        (bands.size > 1) shouldBe true
        for (band in bands) (band.rows <= 800 * 4 + 1000) shouldBe true
        bands.flatMap { it.regions }.sorted() shouldBe regions.indices.toList()
    }
}
