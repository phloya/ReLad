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
                when {
                    // Light letters on dark art: a clean label over them instead of patchy erasing
                    result.onArt -> Placement(region, region.box, dark = true, outlined = false, label = true)
                    else -> {
                        val area = result.erased?.let { insetForText(it, region.box, result.enclosed, width, height) }
                            ?: region.box
                        val dark = BubbleEraser.luminance(result.background) >= 128
                        val shaped = result.rows?.let { rows -> result.erased?.let { Shape(it, rows) } }
                        Placement(region, area, dark = dark, outlined = false, shape = shaped)
                    }
                }
            } else {
                Placement(region, region.box, dark = true, outlined = true)
            }
        }
        // Labels cover the original text: paint them before any text is drawn
        placements.filter { it.label }.forEach { paintLabel(pixels, width, height, it.region.box) }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

        val canvas = Canvas(bitmap)
        placements.forEach { draw(canvas, it, width) }
    }

    private class Placement(
        val region: TextRegion,
        val area: Box,
        val dark: Boolean,
        val outlined: Boolean,
        val label: Boolean = false,
        val shape: Shape? = null,
    )

    /** A bubble's interior row by row (see [BubbleEraser.Result.rows]). */
    private class Shape(val box: Box, val rows: IntArray) {
        val centerX = (box.left + box.right) / 2f
        val centerY = (box.top + box.bottom) / 2f

        /** The widest text block centred on the bubble that fits between rows [top] and [bottom]. */
        fun widthBetween(top: Float, bottom: Float, padding: Float): Float {
            var half = Float.MAX_VALUE
            for (y in top.toInt() until kotlin.math.ceil(bottom).toInt()) {
                val i = (y - box.top) * 2
                if (i < 0 || i + 1 >= rows.size || rows[i] < 0) return 0f
                half = minOf(half, centerX - rows[i], rows[i + 1] - centerX)
            }
            return if (half == Float.MAX_VALUE) 0f else (2 * (half - padding)).coerceAtLeast(0f)
        }
    }

    /** A rounded light label over the original text, a little larger than its box. */
    private fun paintLabel(pixels: IntArray, width: Int, height: Int, box: Box) {
        val label = box.expanded(LABEL_MARGIN, width, height)
        val radius = minOf(label.width, label.height) * 0.18f
        for (y in label.top until label.bottom) {
            for (x in label.left until label.right) {
                val dx = maxOf(label.left + radius - x, x - (label.right - 1 - radius), 0f)
                val dy = maxOf(label.top + radius - y, y - (label.bottom - 1 - radius), 0f)
                if (dx * dx + dy * dy <= radius * radius) pixels[y * width + x] = LABEL_COLOR
            }
        }
    }

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
        val text = placement.region.translation
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            color = if (placement.dark) Color.BLACK else Color.WHITE
        }
        val minSize = MIN_TEXT_SIZE_FRACTION * pageWidth
        val shaped = placement.shape?.let { fitShape(text, paint, it, minSize, pageWidth * 0.12f) }
        val area = shaped?.first ?: placement.area
        val maxSize = maxOf(MIN_TEXT_SIZE_FRACTION * pageWidth, area.height * 0.9f).coerceAtMost(pageWidth * 0.12f)
        val layout = shaped?.second ?: fit(text, paint, area, minSize, maxSize)
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

    /**
     * The largest text size whose lines fit the bubble's real shape: in a round bubble the block
     * may be wide in the middle only as high as the bubble stays that wide. `null` when even the
     * smallest size doesn't fit (the box fit is used then).
     */
    private fun fitShape(text: String, paint: TextPaint, shape: Shape, minSize: Float, maxSize: Float):
        Pair<Box, StaticLayout>? {
        val padX = shape.box.width * SHAPE_PADDING + 2
        val padY = shape.box.height * SHAPE_PADDING + 2
        val maxHeight = shape.box.height - 2 * padY

        fun attempt(size: Float): Pair<Box, StaticLayout>? {
            paint.textSize = size
            var blockHeight = paint.fontSpacing
            repeat(SHAPE_STEPS) {
                if (blockHeight > maxHeight) return null
                val top = shape.centerY - blockHeight / 2
                val width = shape.widthBetween(top, top + blockHeight, padX)
                if (width < size * 2) return null
                val layout = build(text, paint, width.toInt())
                if (layout.height <= blockHeight + 0.5f && !layout.hasBrokenWords()) {
                    val left = (shape.centerX - width / 2).toInt()
                    return Box(left, top.toInt(), left + width.toInt(), (top + blockHeight).toInt()) to layout
                }
                blockHeight = maxOf(layout.height.toFloat(), blockHeight + paint.fontSpacing / 2)
            }
            return null
        }

        var best = attempt(minSize) ?: return null
        var low = minSize
        var high = maxOf(minSize, maxSize)
        repeat(FIT_STEPS) {
            val mid = (low + high) / 2
            val found = attempt(mid)
            if (found != null) {
                best = found
                low = mid
            } else {
                high = mid
            }
        }
        paint.textSize = low
        return best
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
    private const val SHAPE_STEPS = 8

    /** Space between the text and the bubble's outline, as a share of the bubble's size. */
    private const val SHAPE_PADDING = 0.06f
    private const val LABEL_MARGIN = 0.06f
    private val LABEL_COLOR = Color.WHITE
}
