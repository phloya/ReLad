package plus.colorize

import kotlin.math.abs
import kotlin.math.ceil

/**
 * Image models return pictures in a few fixed shapes. The page is padded with white to the
 * closest one, so the model does not stretch or crop it, and the padding is cut off afterwards.
 */
object PageShape {

    /** Shapes Gemini's image models can return (`imageConfig.aspectRatio`). */
    val RATIOS: List<Pair<String, Float>> = listOf(
        "1:1" to 1f,
        "2:3" to 2f / 3,
        "3:2" to 3f / 2,
        "3:4" to 3f / 4,
        "4:3" to 4f / 3,
        "4:5" to 4f / 5,
        "5:4" to 5f / 4,
        "9:16" to 9f / 16,
        "16:9" to 16f / 9,
        "21:9" to 21f / 9,
    )

    /** Taller than this (webtoon strips) or wider (long spreads) is not colored: too much padding, tiny drawing. */
    const val MAX_TALL = 2.2f
    const val MAX_WIDE = 2.6f

    /** A page of [pageWidth]×[pageHeight] placed in the middle of a [width]×[height] canvas of [ratio]. */
    data class Layout(
        val ratio: String,
        val width: Int,
        val height: Int,
        val offsetX: Int,
        val offsetY: Int,
        val pageWidth: Int,
        val pageHeight: Int,
    )

    fun canColor(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && height.toFloat() / width <= MAX_TALL && width.toFloat() / height <= MAX_WIDE

    /** The smallest canvas of a supported shape that holds the page. */
    fun layout(pageWidth: Int, pageHeight: Int): Layout = RATIOS.map { (name, ratio) ->
        val page = pageWidth.toFloat() / pageHeight
        val (w, h) = if (page > ratio) {
            pageWidth to ceil(pageWidth / ratio).toInt()
        } else {
            ceil(pageHeight * ratio).toInt() to pageHeight
        }
        Layout(name, w, h, (w - pageWidth) / 2, (h - pageHeight) / 2, pageWidth, pageHeight)
    }.minWith(compareBy<Layout> { it.width.toLong() * it.height }.thenBy { abs(it.width - it.height) })
}
