package plus.translate

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.TextPaint

/**
 * Draws translations onto a page: [PageLettering] erases the originals and plans the lines,
 * this draws them with Android's text engine in the lettering font ([LetteringFonts]); text the
 * font has no letters for (CJK, Thai, Arabic) is set in the system font.
 */
object PageTypesetter {

    /**
     * Puts the regions onto the text found on the page ([TextPlacer]), erases the originals and
     * sets the translations. Returns the regions as placed.
     */
    fun render(bitmap: Bitmap, regions: List<TextRegion>, language: String, fonts: LetteringFonts): List<TextRegion> {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val placed = TextPlacer.place(pixels, width, height, regions)
        val letterings = PageLettering.plan(pixels, width, height, placed, language, PaintFonts(fonts))
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

        val canvas = Canvas(bitmap)
        letterings.forEach { draw(canvas, it) }
        return placed
    }

    private fun draw(canvas: Canvas, lettering: PageLettering.Lettering) {
        val font = lettering.font as PaintMeasurer
        val thicken = lettering.block.size * lettering.thicken
        val fill = TextPaint(font.paint).apply {
            textSize = lettering.block.size
            color = lettering.fill
            // Leaning as the original's letters (Android leans right for negative values)
            if (lettering.skew != 0f) textSkewX = -lettering.skew
            if (thicken > 0f) {
                // Bolder than the font: a stroke of the fill's colour around the letters
                style = Paint.Style.FILL_AND_STROKE
                strokeWidth = thicken
                strokeJoin = Paint.Join.ROUND
            }
        }
        val outline = lettering.outline?.let { color ->
            TextPaint(fill).apply {
                style = Paint.Style.STROKE
                strokeWidth = fill.textSize * lettering.outlineWidth + thicken
                strokeJoin = Paint.Join.ROUND
                this.color = color
            }
        }
        // A soft glow around the letters, as the original's: their shape blurred in the glow's colour
        val glow = lettering.glow?.let { color ->
            TextPaint(fill).apply {
                this.color = color
                maskFilter = BlurMaskFilter(
                    (lettering.glowRadius * fill.textSize).coerceAtLeast(1f),
                    BlurMaskFilter.Blur.OUTER,
                )
                style = Paint.Style.FILL_AND_STROKE
                strokeWidth = lettering.glowRadius * fill.textSize
                strokeJoin = Paint.Join.ROUND
            }
        }
        // The lean moves a line's top right: half of it back keeps the line where it was
        val leaning = lettering.skew * font.capHeight * fill.textSize / 2
        val turned = lettering.angle != 0f
        if (turned) {
            canvas.save()
            canvas.rotate(lettering.angle, lettering.pivotX, lettering.pivotY)
        }
        for (line in lettering.block.lines) {
            val text = font.drawable(line.text)
            val x = line.centerX - fill.measureText(text) / 2 - leaning
            val rtl = isRtl(text)
            glow?.let { canvas.drawTextRun(text, 0, text.length, 0, text.length, x, line.baseline, rtl, it) }
            // Outline first, then the fill on top: readable over any art
            outline?.let { canvas.drawTextRun(text, 0, text.length, 0, text.length, x, line.baseline, rtl, it) }
            canvas.drawTextRun(text, 0, text.length, 0, text.length, x, line.baseline, rtl, fill)
        }
        if (turned) canvas.restore()
    }

    private fun isRtl(text: String): Boolean {
        for (c in text) {
            when (Character.getDirectionality(c)) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
            }
        }
        return false
    }

    private class PaintFonts(private val fonts: LetteringFonts) : PageLettering.Fonts {
        private val lettering = fonts.regular?.let { PaintMeasurer(it, LetteringFonts.SCALE_X) }
        private val letteringBold = fonts.bold?.let { PaintMeasurer(it, LetteringFonts.SCALE_X) }
        private val system = PaintMeasurer(Typeface.DEFAULT_BOLD, 1f)

        override fun measurer(text: String, bold: Boolean): TextMeasurer {
            val font = (if (bold) letteringBold else lettering) ?: return system
            return if (font.hasLetters(text)) font else system
        }
    }

    /** Measures with a Paint at a reference size; text scales linearly with size. */
    private class PaintMeasurer(typeface: Typeface, scaleX: Float) : TextMeasurer {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.typeface = typeface
            textScaleX = scaleX
            textSize = REFERENCE
        }

        override val capHeight = bounds("H") / REFERENCE
        override val xHeight = bounds("x") / REFERENCE
        override val lineHeight = paint.fontSpacing / REFERENCE

        override fun width(text: String): Float = paint.measureText(drawable(text)) / REFERENCE

        /** Uzbek ʻ (U+02BB), if the font lacks it, is drawn as the ‘ it looks like. */
        fun drawable(text: String): String = if (!paint.hasGlyph("ʻ")) text.replace('ʻ', '‘') else text

        fun hasLetters(text: String): Boolean {
            val drawn = drawable(text)
            var i = 0
            while (i < drawn.length) {
                val next = drawn.offsetByCodePoints(i, 1)
                val c = drawn.substring(i, next)
                if (!c.isBlank() && !paint.hasGlyph(c)) return false
                i = next
            }
            return true
        }

        private fun bounds(text: String): Float {
            val rect = Rect()
            paint.getTextBounds(text, 0, text.length, rect)
            return rect.height().toFloat()
        }
    }

    private const val REFERENCE = 100f
}
