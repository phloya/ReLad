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
 * Text cut where a line may break (after spaces, hyphens; between CJK and Thai letters; inside a
 * long word between syllables, where a hyphen is then added), each piece measured once: a line's
 * width is then a sum, so many line splits can be tried quickly.
 */
class TextPieces private constructor(
    val pieces: List<String>,
    /** Width of each piece with its trailing spaces, at size 1. */
    val full: FloatArray,
    /** Width of each piece without trailing spaces (how it looks at the end of a line). */
    val trimmed: FloatArray,
    /** The piece ends inside a word: a line ending there gets a hyphen ([trimmed] includes it). */
    val soft: BooleanArray = BooleanArray(pieces.size),
) {
    val size: Int get() = pieces.size

    /** Width of the line made of pieces [from] until [to] (exclusive), at size 1. */
    fun width(from: Int, to: Int): Float {
        var sum = 0f
        for (i in from until to - 1) sum += full[i]
        return sum + trimmed[to - 1]
    }

    fun line(from: Int, to: Int): String =
        pieces.subList(from, to).joinToString("").trim() + (if (soft[to - 1]) "-" else "")

    /** The widest single piece: no line can be narrower. */
    val widest: Float get() = trimmed.maxOrNull() ?: 0f

    companion object {
        /**
         * [hyphenate]: long words may also be cut between syllables. A letterer does that only when
         * whole words would leave the text much smaller: try without first.
         */
        fun of(
            text: String,
            measurer: TextMeasurer,
            locale: Locale,
            hyphenate: Boolean = false,
            /** With [hyphenate]: only words wider than this (at size 1) are cut. */
            cutWiderThan: Float = 0f,
        ): TextPieces {
            val clean = text.replace('\n', ' ').trim()
            val iterator = BreakIterator.getLineInstance(locale)
            iterator.setText(clean)
            val words = mutableListOf<String>()
            var start = iterator.first()
            var end = iterator.next()
            while (end != BreakIterator.DONE) {
                val piece = clean.substring(start, end)
                // A short word joined by a hyphen stays whole: "что-/то", "кто-/нибудь", "какой-/то",
                // "Су-/хо" are not split ("Цукишима-/кун" may be). As a last resort, any may
                val last = words.lastOrNull()
                if (!hyphenate && last != null && last.endsWith('-') &&
                    (letters(last) <= SHORT_PART || letters(piece) <= SHORT_END)
                ) {
                    words[words.size - 1] = last + piece
                } else {
                    words += piece
                }
                start = end
                end = iterator.next()
            }
            val pieces = mutableListOf<String>()
            val soft = mutableListOf<Boolean>()
            for (word in marksJoined(words)) {
                val cut = hyphenate && measurer.width(word.trim()) > cutWiderThan
                val parts = if (cut) Hyphenation.split(word) else listOf(word)
                parts.forEachIndexed { k, part ->
                    pieces += part
                    soft += k < parts.size - 1
                }
            }
            return TextPieces(
                pieces = pieces,
                full = FloatArray(pieces.size) { measurer.width(pieces[it]) },
                trimmed = FloatArray(pieces.size) {
                    measurer.width(pieces[it].trimEnd() + (if (soft[it]) "-" else ""))
                },
                soft = soft.toBooleanArray(),
            )
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
            return TextPieces(
                pieces = pieces,
                full = FloatArray(pieces.size) { measurer.width(pieces[it]) },
                trimmed = FloatArray(pieces.size) { measurer.width(pieces[it].trimEnd()) },
            )
        }

        /**
         * Marks with no letters stay with a word: "…" before one ("…Побыть": a line may break after it
         * on a phone, whose line breaking follows Unicode's), a dash after one ("одному —": a line
         * doesn't start with it), a dash opening the text before its first word.
         */
        private fun marksJoined(words: List<String>): List<String> {
            val joined = mutableListOf<String>()
            var before = ""
            for (word in words) {
                val bare = word.none { it.isLetterOrDigit() }
                when {
                    bare && word.last().isWhitespace() && joined.isNotEmpty() && before.isEmpty() ->
                        joined[joined.size - 1] += word
                    bare -> before += word
                    else -> {
                        joined += before + word
                        before = ""
                    }
                }
            }
            if (before.isNotEmpty()) {
                if (joined.isEmpty()) joined += before else joined[joined.size - 1] += before
            }
            return joined
        }

        private fun letters(text: String) = text.count { it.isLetter() }

        /** A hyphenated word whose first part is this short (что-то, кто-нибудь)... */
        private const val SHORT_PART = 3

        /** ...or its last (какой-то, Су-хо) stays on one line. */
        private const val SHORT_END = 2
    }
}

/**
 * Where a long word may be cut at the end of a line, as Russian (and other Cyrillic) typesetting
 * does it: between syllables, a consonant going with the vowel after it, never leaving a lone letter
 * or a part without a vowel, never before й, ь, ъ. Other scripts are not cut.
 */
internal object Hyphenation {

    /** [word] (with its trailing spaces and marks) in the parts it may be cut into; whole if none. */
    fun split(word: String): List<String> {
        val letters = word.indices.filter { word[it].isLetter() }
        if (letters.size < MIN_WORD || letters.any { !isCyrillic(word[it]) }) return listOf(word)
        // Cut points, as indices into word, between two letters of the word's letter run
        val first = letters.first()
        val last = letters.last()
        if (last - first + 1 != letters.size) return listOf(word)
        val cuts = mutableListOf<Int>()
        for (i in first + MIN_PART..last + 1 - MIN_PART) {
            val a = word[i - 1].lowercaseChar()
            val b = word[i].lowercaseChar()
            if (b in NO_START) continue
            // Not between two vowels; a consonant stays with the vowel after it ("ко-шка", not "кошк-а");
            // after a vowel only before a single consonant: a cluster is cut inside ("вает-ся", not "вае-тся")
            if (b in VOWELS && (a in VOWELS || a !in SIGNS)) continue
            if (a in VOWELS && i + 1 <= last && word[i + 1].lowercaseChar() !in VOWELS) continue
            val left = word.substring(first, i)
            val right = word.substring(i, last + 1)
            if (left.none { it.lowercaseChar() in VOWELS } || right.none { it.lowercaseChar() in VOWELS }) continue
            // The previous cut's part needs a vowel too
            val previous = cuts.lastOrNull() ?: first
            if (word.substring(previous, i).none { it.lowercaseChar() in VOWELS }) continue
            cuts += i
        }
        if (cuts.isEmpty()) return listOf(word)
        val parts = mutableListOf<String>()
        var from = 0
        for (cut in cuts) {
            parts += word.substring(from, cut)
            from = cut
        }
        parts += word.substring(from)
        return parts
    }

    private fun isCyrillic(c: Char) = Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC

    /** Vowels of Russian, Ukrainian, Kazakh, Belarusian. */
    private const val VOWELS = "аеёиоуыэюяіїєәөұүі"
    private const val SIGNS = "ьъй"
    private const val NO_START = "ьъй"

    /** Words shorter than this are never cut; each part has at least [MIN_PART] letters. */
    private const val MIN_WORD = 7
    private const val MIN_PART = 2
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
                        // A word cut by a hyphen only when it pays: a line much fuller or the text fitting
                        val cost = before + slack * slack + (if (text.soft[j - 1]) HYPHEN_COST else 0f)
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
        // The text never ends cut
        if (text.soft[count - 1]) return null
        val ends = IntArray(lines)
        var end = count
        for (k in lines downTo 1) {
            ends[k - 1] = end
            end = from[k][end]
        }
        return ends
    }

    /** What ending a line inside a word costs, against the squared empty share of each line. */
    private const val HYPHEN_COST = 0.35f
}
