package plus.translate

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Kinds of text on a manga page; each is drawn differently. */
@Serializable
enum class RegionType {
    /** Speech or thought bubble: the original text is erased and the translation set inside. */
    @SerialName("bubble")
    Bubble,

    /** Narration box: handled like a bubble. */
    @SerialName("caption")
    Caption,

    /** Sound effect drawn on the art: the translation is drawn over it, the art stays. */
    @SerialName("sfx")
    Sfx,

    /** Other text on the art (signs, notes, handwriting): like a sound effect. */
    @SerialName("other")
    Other,
    ;

    val erasesOriginal: Boolean get() = this == Bubble || this == Caption
}

/**
 * One piece of text found on the page. [box] is in pixels of the original page:
 * left, top, right, bottom.
 */
@Serializable
data class TextRegion(
    val type: RegionType,
    val box: Box,
    val text: String,
    val translation: String,
)

@Serializable
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    /** Grows the box by [pixels] on every side, staying inside the page. */
    fun grown(pixels: Int, pageWidth: Int, pageHeight: Int) = Box(
        left = (left - pixels).coerceAtLeast(0),
        top = (top - pixels).coerceAtLeast(0),
        right = (right + pixels).coerceAtMost(pageWidth),
        bottom = (bottom + pixels).coerceAtMost(pageHeight),
    )

    /** Grows the box by [fraction] of its size on every side, staying inside the page. */
    fun expanded(fraction: Float, pageWidth: Int, pageHeight: Int): Box {
        val dx = (width * fraction).toInt()
        val dy = (height * fraction).toInt()
        return Box(
            left = (left - dx).coerceAtLeast(0),
            top = (top - dy).coerceAtLeast(0),
            right = (right + dx).coerceAtMost(pageWidth),
            bottom = (bottom + dy).coerceAtMost(pageHeight),
        )
    }
}

/** What the translator stores next to a translated page (for a future "show original" overlay). */
@Serializable
data class TranslatedPage(
    val language: String,
    val regions: List<TextRegion>,
)
