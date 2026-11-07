package plus.translate

import java.text.BreakIterator
import java.util.Locale

/** Measures text in the lettering font. Widths are at text size 1: multiply by the size. */
interface TextMeasurer {
    fun width(text: String): Float

    /** Height of capital letters, as a share of the text size. */
    val capHeight: Float

    /** Height of lower-case x, as a share of the text size. */
    val xHeight: Float

    /** Distance between baselines the font is made for, as a share of the text size. */
    val lineHeight: Float
}

/**
 * Text cut where a line may break (after spaces, hyphens; between CJK and Thai letters), each
 * piece measured once: a line's width is then a sum, so many line splits can be tried quickly.
 */
class TextPieces private constructor(
    val pieces: List<String>,
    /** Width of each piece with its trailing spaces, at size 1. */
    val full: FloatArray,
    /** Width of each piece without trailing spaces (how it looks at the end of a line). */
    val trimmed: FloatArray,
) {
    val size: Int get() = pieces.size

    /** Width of the line made of pieces [from] until [to] (exclusive), at size 1. */
    fun width(from: Int, to: Int): Float {
        var sum = 0f
        for (i in from until to - 1) sum += full[i]
        return sum + trimmed[to - 1]
    }

    fun line(from: Int, to: Int): String = pieces.subList(from, to).joinToString("").trim()

    /** The widest single piece: no line can be narrower. */
    val widest: Float get() = trimmed.maxOrNull() ?: 0f

    companion object {
        fun of(text: String, measurer: TextMeasurer, locale: Locale): TextPieces {
            val clean = text.replace('\n', ' ').trim()
            val iterator = BreakIterator.getLineInstance(locale)
            iterator.setText(clean)
            val pieces = mutableListOf<String>()
            var start = iterator.first()
            var end = iterator.next()
            while (end != BreakIterator.DONE) {
                pieces += clean.substring(start, end)
                start = end
                end = iterator.next()
            }
            return from(pieces, measurer)
        }

        /** Every character its own piece: the last resort for a word too long for any line. */
        fun characters(text: String, measurer: TextMeasurer): TextPieces {
            val clean = text.replace('\n', ' ').trim()
            val pieces = mutableListOf<String>()
            var i = 0
            while (i < clean.length) {
                val next = clean.offsetByCodePoints(i, 1)
                pieces += clean.substring(i, next)
                i = next
            }
            return from(pieces, measurer)
        }

        private fun from(pieces: List<String>, measurer: TextMeasurer) = TextPieces(
            pieces = pieces,
            full = FloatArray(pieces.size) { measurer.width(pieces[it]) },
            trimmed = FloatArray(pieces.size) { measurer.width(pieces[it].trimEnd()) },
        )
    }
}

/**
 * Splits text into a given number of lines, each with its own available width: lines in a round
 * bubble are short at the top and bottom and long in the middle, as a letterer sets them.
 */
object LineBreaker {

    /**
     * The split of [text] into exactly `widths.size` lines that fit their widths (in pixels at
     * [size]), each line filled about as much as the others. Returns where each line ends (piece
     * index, exclusive), or `null` when the text can't fit.
     */
    fun split(text: TextPieces, size: Float, widths: FloatArray): IntArray? {
        val lines = widths.size
        val count = text.size
        if (lines == 0 || count < lines) return null
        val total = text.width(0, count) * size
        if (total > widths.sum()) return null

        val inf = Float.MAX_VALUE
        // best[k][j]: cost of the first j pieces in k lines; from[k][j]: where line k starts
        val best = Array(lines + 1) { FloatArray(count + 1) { inf } }
        val from = Array(lines + 1) { IntArray(count + 1) }
        best[0][0] = 0f
        for (k in 1..lines) {
            val available = widths[k - 1]
            // Each earlier line needs a piece, and so does each later one
            for (j in k..count - (lines - k)) {
                var start = j - 1
                var width = text.trimmed[j - 1] * size
                while (start >= k - 1 && width <= available) {
                    val before = best[k - 1][start]
                    if (before != inf) {
                        val slack = (available - width) / available
                        val cost = before + slack * slack
                        if (cost < best[k][j]) {
                            best[k][j] = cost
                            from[k][j] = start
                        }
                    }
                    start--
                    if (start >= 0) width += text.full[start] * size
                }
            }
        }
        if (best[lines][count] == inf) return null
        val ends = IntArray(lines)
        var end = count
        for (k in lines downTo 1) {
            ends[k - 1] = end
            end = from[k][end]
        }
        return ends
    }
}
