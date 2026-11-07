package plus.translate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * Draws translations onto a page: bubbles and captions are erased ([BubbleEraser]) and the
 * translation is set inside; sound effects and other text on the art get the translation drawn
 * over them with an outline, the art itself stays.
 *
 * Uses the system font, which covers every script (Cyrillic incl. Kazakh letters, Uzbek ʻ,
 * CJK, Thai, Arabic) and needs no font licence.
 */
object PageTypesetter {

    fun render(bitmap: Bitmap, regions: List<TextRegion>) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val placements = regions.map { region ->
            if (region.type.erasesOriginal) {
                val result = BubbleEraser.erase(
                    pixels = pixels,
                    width = width,
                    height = height,
                    textBox = region.box,
                    // By the longer side: a column of vertical Japanese text sits in a bubble
                    // several times wider than the column
                    searchBox = region.box.grown(
                        (maxOf(region.box.width, region.box.height) * SEARCH_MARGIN).toInt(),
                        width,
                        height,
                    ),
                )
                val area = result.erased?.let { insetForText(it, region.box, result.enclosed, width, height) }
                    ?: region.box
                Placement(region, area, dark = BubbleEraser.luminance(result.background) >= 128, outlined = false)
            } else {
                Placement(region, region.box, dark = true, outlined = true)
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

        val canvas = Canvas(bitmap)
        placements.forEach { draw(canvas, it, width) }
    }

    private class Placement(val region: TextRegion, val area: Box, val dark: Boolean, val outlined: Boolean)

    /**
     * Text goes in the middle part of the erased bubble (bubbles are round, corners are outside),
     * but never narrower than the original text box.
     */
    private fun insetForText(erased: Box, textBox: Box, enclosed: Boolean, pageWidth: Int, pageHeight: Int): Box {
        val dx = (erased.width * BUBBLE_INSET_X).toInt()
        val dy = (erased.height * BUBBLE_INSET_Y).toInt()
        // A bubble with a closed outline is used whole (tall bubbles of vertical Japanese text are
        // much wider than the text column). Otherwise stay near the original text: on a white page
        // the "bubble" found can be much larger than the real one.
        val limit = if (enclosed) erased else textBox.expanded(MAX_GROWTH, pageWidth, pageHeight)
        return Box(
            left = minOf(maxOf(erased.left + dx, limit.left), textBox.left),
            top = minOf(maxOf(erased.top + dy, limit.top), textBox.top),
            right = maxOf(minOf(erased.right - dx, limit.right), textBox.right),
            bottom = maxOf(minOf(erased.bottom - dy, limit.bottom), textBox.bottom),
        )
    }

    private fun draw(canvas: Canvas, placement: Placement, pageWidth: Int) {
        val area = placement.area
        val text = placement.region.translation
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            color = if (placement.dark) Color.BLACK else Color.WHITE
        }
        val maxSize = maxOf(MIN_TEXT_SIZE_FRACTION * pageWidth, area.height * 0.9f).coerceAtMost(pageWidth * 0.12f)
        val minSize = MIN_TEXT_SIZE_FRACTION * pageWidth
        val layout = fit(text, paint, area, minSize, maxSize)
        val top = area.top + (area.height - layout.height) / 2f

        canvas.save()
        canvas.translate(area.left.toFloat(), top)
        if (placement.outlined) {
            // Outline first, then the fill on top: readable over any art
            val outline = TextPaint(paint).apply {
                style = Paint.Style.STROKE
                strokeWidth = paint.textSize * 0.18f
                strokeJoin = Paint.Join.ROUND
                color = Color.WHITE
            }
            build(text, outline, area.width).draw(canvas)
        }
        layout.draw(canvas)
        canvas.restore()
    }

    /** The largest text size whose layout fits [area]; at [minSize] it may run over. */
    private fun fit(text: String, paint: TextPaint, area: Box, minSize: Float, maxSize: Float): StaticLayout {
        var low = minSize
        var high = maxOf(minSize, maxSize)
        paint.textSize = low
        var best = build(text, paint, area.width)
        repeat(FIT_STEPS) {
            val mid = (low + high) / 2
            paint.textSize = mid
            val layout = build(text, paint, area.width)
            if (layout.height <= area.height && !layout.hasBrokenWords()) {
                best = layout
                low = mid
            } else {
                high = mid
            }
        }
        paint.textSize = low
        return best
    }

    private fun build(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, LINE_SPACING)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            // No hyphenation: a word split mid-way then always means "too big", see hasBrokenWords
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setUseLineSpacingFromFallbacks(true) }
            .build()

    /**
     * A line break inside a word (or before its punctuation) means the text is too big for the
     * width. Breaks are fine after spaces and hyphens, and anywhere in CJK and Thai text.
     */
    private fun StaticLayout.hasBrokenWords(): Boolean {
        for (line in 0 until lineCount - 1) {
            val end = getLineEnd(line)
            if (end <= 0 || end >= text.length) continue
            val before = text[end - 1]
            val after = text[end]
            val fine = before.isWhitespace() || before == '-' || before == '‐' || before == '—' ||
                isCjk(before) || isCjk(after)
            if (!fine) return true
        }
        return false
    }

    private fun isCjk(c: Char): Boolean = Character.UnicodeScript.of(c.code) in setOf(
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL,
        Character.UnicodeScript.THAI,
    )

    /** How far around the text box the bubble may extend, as a share of the box's longer side. */
    private const val SEARCH_MARGIN = 1.0f

    // Text lines sit near the middle of a round bubble, where it is widest: less inset at the sides
    private const val BUBBLE_INSET_X = 0.08f
    private const val BUBBLE_INSET_Y = 0.14f
    private const val MAX_GROWTH = 0.5f
    private const val MIN_TEXT_SIZE_FRACTION = 0.018f
    private const val LINE_SPACING = 1.0f
    private const val FIT_STEPS = 12
}
