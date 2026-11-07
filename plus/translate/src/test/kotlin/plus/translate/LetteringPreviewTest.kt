package plus.translate

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.font.TextLayout
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Letters real pages on the computer, to look at the result without a phone: the same
 * [PageLettering] as the app, drawn with Java2D instead of Android. Run by hand:
 *
 *     RELAD_PREVIEW_PAGES=/path/chapter RELAD_PREVIEW_JSON=/live/test/out RELAD_PREVIEW_OUT=/tmp/preview \
 *       RELAD_PREVIEW_FONT=/path/font.ttf ./gradlew :plus:translate:testDebugUnitTest --tests '*LetteringPreviewTest*'
 *
 * Pages are matched to "<chapter dir name>-<page name>.json" of a [LiveTranslationTest] run.
 * RELAD_PREVIEW_FONT: a static instance of the lettering font (Java2D can't set a variable
 * font's weight); without it the system font is used.
 */
@EnabledIfEnvironmentVariable(named = "RELAD_PREVIEW_PAGES", matches = ".+")
class LetteringPreviewTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val context = FontRenderContext(null, true, true)

    @Test
    fun `letter real pages`() {
        val env = System.getenv()
        val out = File(env.getValue("RELAD_PREVIEW_OUT")).apply { mkdirs() }
        val answers = File(env.getValue("RELAD_PREVIEW_JSON"))
        val lettering = env["RELAD_PREVIEW_FONT"]?.let { Font.createFont(Font.TRUETYPE_FONT, File(it)) }
        val system = Font(Font.SANS_SERIF, Font.BOLD, 1)
        for (dir in env.getValue("RELAD_PREVIEW_PAGES").split(':').map(::File)) {
            val pages = dir.listFiles { f ->
                f.extension.lowercase() in setOf("jpg", "jpeg", "png")
            }!!.sortedBy { it.name }
            for (file in pages) {
                val data = File(answers, "${dir.name}-${file.nameWithoutExtension}.json")
                if (!data.exists()) continue
                val page = json.decodeFromString(TranslatedPage.serializer(), data.readText())
                val image = ImageIO.read(file)
                val width = image.width
                val height = image.height
                val pixels = image.getRGB(0, 0, width, height, null, 0, width)
                val fonts = PageLettering.Fonts { text, _ ->
                    if (lettering != null && lettering.canDisplayUpTo(text.replace('ʻ', '‘')) == -1) {
                        AwtMeasurer(lettering, LetteringFonts.SCALE_X)
                    } else {
                        AwtMeasurer(system, 1f)
                    }
                }
                val log = StringBuilder()
                val plans = PageLettering.plan(pixels, width, height, page.regions, page.language, fonts) {
                    log.append(it).append('\n')
                }
                File(out, "${dir.name}-${file.nameWithoutExtension}.txt").writeText(log.toString())
                val result = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
                result.setRGB(0, 0, width, height, pixels, 0, width)
                val g = result.createGraphics()
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
                for (plan in plans) {
                    val measurer = plan.font as AwtMeasurer
                    val font = measurer.font.deriveFont(
                        AffineTransform.getScaleInstance(
                            measurer.scaleX.toDouble() * plan.block.size,
                            plan.block.size.toDouble(),
                        ),
                    )
                    for (line in plan.block.lines) {
                        val text = line.text.replace('ʻ', '‘')
                        val x = line.centerX - measurer.width(text) * plan.block.size / 2
                        val shape = font.createGlyphVector(context, text).getOutline(x, line.baseline)
                        plan.outline?.let {
                            g.color = Color(it)
                            g.stroke =
                                BasicStroke(
                                    plan.block.size * plan.outlineWidth,
                                    BasicStroke.CAP_ROUND,
                                    BasicStroke.JOIN_ROUND,
                                )
                            g.draw(shape)
                        }
                        g.color = Color(plan.fill)
                        g.fill(shape)
                    }
                }
                g.dispose()
                ImageIO.write(result, "png", File(out, "${dir.name}-${file.nameWithoutExtension}.png"))
            }
        }
    }

    private inner class AwtMeasurer(base: Font, val scaleX: Float) : TextMeasurer {
        val font: Font = base.deriveFont(1f)
        private val reference = base.deriveFont(AffineTransform.getScaleInstance(scaleX * 100.0, 100.0))

        override fun width(text: String): Float =
            if (text.isEmpty()) 0f else reference.getStringBounds(text, context).width.toFloat() / 100

        override val capHeight = TextLayout("H", base.deriveFont(100f), context).bounds.height.toFloat() / 100
        override val xHeight = TextLayout("x", base.deriveFont(100f), context).bounds.height.toFloat() / 100
        override val lineHeight =
            base.deriveFont(100f).getLineMetrics("Hg", context).let { it.ascent + it.descent + it.leading } / 100
    }
}
