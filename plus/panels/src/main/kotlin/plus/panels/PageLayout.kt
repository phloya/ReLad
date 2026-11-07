package plus.panels

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.max
import kotlin.math.min

/** A grayscale image: one byte of luminance (0 black … 255 white) per pixel, row by row. */
class GrayImage(val width: Int, val height: Int, val pixels: ByteArray) {
    init {
        require(width > 0 && height > 0 && pixels.size == width * height) { "Bad image size" }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xFF
}

/** A rectangle in coordinates relative to the page: 0 is the left or top edge, 1 the right or bottom one. */
@Serializable
data class Box(
    @SerialName("l") val left: Float,
    @SerialName("t") val top: Float,
    @SerialName("r") val right: Float,
    @SerialName("b") val bottom: Float,
) {
    val width get() = right - left
    val height get() = bottom - top
    val area get() = max(0f, width) * max(0f, height)
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2

    fun intersection(other: Box): Float {
        val w = min(right, other.right) - max(left, other.left)
        val h = min(bottom, other.bottom) - max(top, other.top)
        return if (w <= 0f || h <= 0f) 0f else w * h
    }

    fun union(other: Box) = Box(
        min(left, other.left),
        min(top, other.top),
        max(right, other.right),
        max(bottom, other.bottom),
    )

    companion object {
        val FULL = Box(0f, 0f, 1f, 1f)
    }
}

/** A horizontal band of a page, from [start] to [end] (0 = top, 1 = bottom). */
@Serializable
data class Span(
    @SerialName("s") val start: Float,
    @SerialName("e") val end: Float,
)

/**
 * What the detector found on one page image. Panels are in the order they were found;
 * [ReadingOrder] sorts them for the reading direction, so one result serves manga and comics.
 */
@Serializable
data class PageLayout(
    /** Panels, relative to the page. A page without visible panels has one panel: the whole page. */
    val panels: List<Box>,
    /** What Mihon's "crop borders" leaves of the page. */
    val crop: Box,
    /** Empty horizontal bands (between the panels of a vertical strip), top to bottom. */
    val gaps: List<Span>,
    /** Width / height of the page. */
    val aspect: Float,
) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String): PageLayout? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
    }
}
