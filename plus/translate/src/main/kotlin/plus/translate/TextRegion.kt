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
    /** Original name of who says it, when the model was sure (bubble tail, face, panel). */
    val speaker: String? = null,
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

/**
 * What the translator stores next to a translated page: the lines (for a future "show original"
 * overlay) and what the page cost, for the chapter's price in the app.
 */
@Serializable
data class TranslatedPage(
    val language: String,
    val regions: List<TextRegion>,
    val model: String = "",
    val usage: PageUsage? = null,
    /** Lines the checks sent back for a second look. */
    val fixedLines: Int = 0,
)

/** Tokens all requests for one page used (the translation and, if needed, the second look). */
@Serializable
data class PageUsage(
    val inputTokens: Long,
    val cachedInputTokens: Long,
    val cacheWriteTokens: Long,
    val outputTokens: Long,
    val reportedCostUsd: Double? = null,
) {
    companion object {
        fun of(usage: List<plus.core.ai.AiUsage>): PageUsage? = if (usage.isEmpty()) {
            null
        } else {
            PageUsage(
                inputTokens = usage.sumOf { it.inputTokens },
                cachedInputTokens = usage.sumOf { it.cachedInputTokens },
                cacheWriteTokens = usage.sumOf { it.cacheWriteTokens },
                outputTokens = usage.sumOf { it.outputTokens },
                reportedCostUsd = usage.mapNotNull { it.reportedCostUsd }.takeIf { it.size == usage.size }?.sum(),
            )
        }
    }
}
