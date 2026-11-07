package plus.translate

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import javax.imageio.ImageIO

/**
 * Puts the regions of finished [LiveTranslationTest] runs onto the text found on their pages,
 * without asking the model again: to tune [TextFinder] and [TextPlacer] and to compare runs. Run by hand:
 *
 *     RELAD_REPLAY_PAGES="/path/Chapter 1:/path/Chapter 2" RELAD_REPLAY_RUNS=/live/run1:/live/run2 \
 *       RELAD_REPLAY_OUT=/tmp/placed ./gradlew :plus:translate:testDebugUnitTest --tests '*TextPlacerReplayTest*'
 *
 * Writes each run's pages with the boxes moved into RELAD_REPLAY_OUT/<run name>/, with a log of
 * what moved where, and the texts found on each page into RELAD_REPLAY_OUT/found/.
 */
@EnabledIfEnvironmentVariable(named = "RELAD_REPLAY_PAGES", matches = ".+")
class TextPlacerReplayTest {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    @Test
    fun `place recorded regions on found text`() {
        val env = System.getenv()
        val out = File(env.getValue("RELAD_REPLAY_OUT")).apply { mkdirs() }
        val runs = env.getValue("RELAD_REPLAY_RUNS").split(':').map(::File)
        val found = File(out, "found").apply { mkdirs() }
        var spent = 0L
        var pagesDone = 0
        for (dir in env.getValue("RELAD_REPLAY_PAGES").split(':').map(::File)) {
            val pages = dir.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }!!
                .sortedBy { it.name }
            for (file in pages) {
                val name = "${dir.name}-${file.nameWithoutExtension}"
                val image = ImageIO.read(file)
                val width = image.width
                val height = image.height
                val pixels = image.getRGB(0, 0, width, height, null, 0, width)
                val started = System.nanoTime()
                val texts = TextFinder.find(pixels, width, height)
                spent += System.nanoTime() - started
                pagesDone++
                File(found, "$name.boxes").writeText(
                    texts.joinToString("\n") { t ->
                        (if (t.light) "L " else "D ") +
                            t.pieces.joinToString(" ") { "${it.left},${it.top},${it.right},${it.bottom}" }
                    },
                )
                File(found, "$name.txt").writeText(
                    texts.joinToString("\n") { t ->
                        "${if (t.light) "light" else "dark"} ${t.box} h=${t.letterHeight} letters=${t.letters} " +
                            "chars=${"%.1f".format(t.chars)} lines=${t.lines.size} q=${"%.2f".format(t.quality)}"
                    },
                )
                for (run in runs) {
                    val data = File(run, "$name.json")
                    if (!data.exists()) continue
                    val page = json.decodeFromString(TranslatedPage.serializer(), data.readText())
                    val log = StringBuilder()
                    val placed = TextPlacer.place(pixels, width, height, page.regions) { log.append(it).append('\n') }
                    val target = File(out, run.name).apply { mkdirs() }
                    File(target, "$name.json").writeText(
                        json.encodeToString(TranslatedPage.serializer(), page.copy(regions = placed)),
                    )
                    File(target, "$name.txt").writeText(log.toString())
                }
            }
        }
        File(out, "timing.txt").writeText("TextFinder: ${spent / 1_000_000 / pagesDone.coerceAtLeast(1)} ms a page\n")
    }
}
