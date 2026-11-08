package plus.translate

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import javax.imageio.ImageIO

/**
 * Where drawing a page's translation spends its time, step by step, as the phone runs it
 * ([PageTypesetter.render] without Android's own decoding and drawing):
 *
 *     RELAD_PROFILE_PAGES="/path/Chapter" RELAD_PROFILE_RUN=/live/run RELAD_PROFILE_OUT=/tmp/profile.txt \
 *       ./gradlew :plus:translate:testDebugUnitTest --tests '*RenderProfileTest*'
 *
 * Pages are matched to "<chapter dir name>-<page name>.json" of the run, as in [TextPlacerReplayTest].
 */
@EnabledIfEnvironmentVariable(named = "RELAD_PROFILE_PAGES", matches = ".+")
class RenderProfileTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val measurer = object : TextMeasurer {
        override fun width(text: String) = text.length * 0.6f
        override val capHeight = 0.7f
        override val xHeight = 0.5f
        override val lineHeight = 1.3f
    }

    @Test
    fun `time each step of drawing`() {
        val env = System.getenv()
        val run = File(env.getValue("RELAD_PROFILE_RUN"))
        val report = StringBuilder()
        // The first page warms the JIT up: timed twice, the second counts
        for (dir in env.getValue("RELAD_PROFILE_PAGES").split(':').map(::File)) {
            val pages = dir.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }!!
                .sortedBy { it.name }
            for ((k, file) in pages.withIndex()) {
                val name = "${dir.name}-${file.nameWithoutExtension}"
                val data = File(run, "$name.json")
                if (!data.exists()) continue
                val regions = json.decodeFromString(TranslatedPage.serializer(), data.readText()).regions
                val image = ImageIO.read(file)
                repeat(if (k == 0) 2 else 1) {
                    val width = image.width
                    val height = image.height
                    val pixels = image.getRGB(0, 0, width, height, null, 0, width)
                    var t = System.nanoTime()
                    fun lap(): Long = (System.nanoTime() - t).also { t = System.nanoTime() } / 1_000_000
                    // As the phone does it ([PageTypesetter.render]): band by band
                    val steps = LinkedHashMap<String, Long>()
                    var place = 0L
                    var plan = 0L
                    var rows = 0
                    var moved = 0
                    var biggest = 0
                    var bands = 0
                    val whole = TextPlacer.place(pixels, width, height, regions)
                    lap()
                    for (band in PageBands.of(width, height, regions)) {
                        val part = pixels.copyOfRange(band.top * width, band.bottom * width)
                        val local = band.regions.map { PageBands.shifted(regions[it], band.top) }
                        val placed = TextPlacer.place(part, width, band.rows, local)
                        place += lap()
                        PageLettering.plan(
                            part,
                            width,
                            band.rows,
                            placed,
                            "ru",
                            { _, _ -> measurer },
                            pageHeight = height,
                            steps = steps,
                        )
                        plan += lap()
                        rows += band.rows
                        biggest = maxOf(biggest, band.rows)
                        bands++
                        // Placed as on the whole page?
                        band.regions.forEachIndexed { k, i ->
                            if (PageBands.shifted(placed[k], -band.top).box != whole[i].box) moved++
                        }
                    }
                    report.append(
                        "$name ${width}x$height regions=${regions.size} bands=$bands rows=$rows biggest=$biggest " +
                            "placedOtherwise=$moved " +
                            "place=$place plan=$plan " +
                            steps.entries.joinToString(" ") { "${it.key}=${it.value}" } + "\n",
                    )
                }
            }
        }
        File(env.getValue("RELAD_PROFILE_OUT")).writeText(report.toString())
    }
}
