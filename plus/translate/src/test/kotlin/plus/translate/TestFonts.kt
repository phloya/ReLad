package plus.translate

import java.awt.BasicStroke
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Shape
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform
import java.io.File

/**
 * The letters test pages are lettered with: the app's own lettering font, from its file. A system
 * font is a different font on every machine (Lucida Grande on a Mac, DejaVu Sans on Linux), its
 * letters other widths and its dots other sizes, so a page drawn with it is found as other texts; a
 * font loaded from a file is drawn from its own outlines everywhere.
 */
internal object TestFonts {

    /** Shantell Sans, its default (light) instance: Java2D doesn't set a variable font's weight. */
    private val lettering: Font = File("src/main/assets/relad/fonts/ShantellSans.ttf").inputStream()
        .use { Font.createFont(Font.TRUETYPE_FONT, it) }

    /** The outlines' own widths, not widths fitted to whole pixels by the font's hints. */
    private val context = FontRenderContext(null, true, true)

    /**
     * Makes the light letters bold, as a share of the size: the stroke drawn around them, and as
     * much more room between them (a bold font's letters are wider, not closer together).
     */
    private const val BOLD = 0.08f

    /** [text] at [size], its baseline from (0, 0); [slant] leans it right. */
    fun outline(text: String, size: Float, slant: Double = 0.0): Shape {
        val font = lettering.deriveFont(
            AffineTransform(size.toDouble(), 0.0, -slant * size, size.toDouble(), 0.0, 0.0),
        )
        val glyphs = font.createGlyphVector(context, text)
        for (i in 0..glyphs.numGlyphs) {
            val at = glyphs.getGlyphPosition(i)
            at.setLocation(at.x + i * size * BOLD, at.y)
            glyphs.setGlyphPosition(i, at)
        }
        return glyphs.outline
    }

    /** The width of the stroke that makes letters at [size] bold. */
    fun bold(size: Float) = size * BOLD

    /** Draws [shape], an [outline] at [size], bold in [g]'s colour. */
    fun fill(g: Graphics2D, shape: Shape, size: Float) {
        g.fill(shape)
        g.stroke = BasicStroke(bold(size), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(shape)
    }
}
