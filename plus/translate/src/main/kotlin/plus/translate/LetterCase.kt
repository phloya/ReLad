package plus.translate

import java.util.Locale

/**
 * Upper or ordinary case for a translation, after the original: a letterer's all-caps bubbles get
 * an all-caps translation, sentence-case narration stays sentence case. Text with no case at all
 * (Japanese, Chinese, Korean) gets capitals in bubbles and captions, as comics are lettered in
 * English, Russian and the other languages with capital letters.
 */
object LetterCase {

    fun upper(original: String, type: RegionType): Boolean {
        var upper = 0
        var lower = 0
        for (c in original) {
            if (Character.isUpperCase(c)) {
                upper++
            } else if (Character.isLowerCase(c)) {
                lower++
            }
        }
        if (upper + lower < MIN_CASED) return type != RegionType.Other
        return upper >= (upper + lower) * UPPER_SHARE
    }

    fun apply(text: String, upper: Boolean, locale: Locale): String = if (upper) text.uppercase(locale) else text

    /**
     * The translation without quotation marks around all of it, when the original has none: a
     * bubble's line is speech already, a letterer never quotes it (models sometimes do).
     */
    fun withoutQuotes(translation: String, original: String): String {
        val line = translation.trim()
        if (line.length < 2 || QUOTES.any { (open, _) -> original.trim().startsWith(open) }) return translation
        for ((open, close) in QUOTES) {
            if (!line.startsWith(open)) continue
            val end = line.lastIndexOf(close)
            // One pair around the whole line, perhaps with the line's own "!!" or "?" after it
            if (end <= 0 || line.indexOf(open, 1) in 1 until end) continue
            val tail = line.substring(end + 1)
            if (tail.any { it !in TRAILING }) continue
            return (line.substring(open.length, end) + tail).trim()
        }
        return translation
    }

    private val QUOTES = listOf("«" to "»", "„" to "“", "“" to "”", "\"" to "\"", "'" to "'", "「" to "」", "『" to "』")
    private const val TRAILING = "!?.…"

    /** Japanese and Chinese characters: their size is measured differently from letters with case. */
    fun isCjk(text: String): Boolean = text.any {
        val script = Character.UnicodeScript.of(it.code)
        script == Character.UnicodeScript.HAN ||
            script == Character.UnicodeScript.HIRAGANA ||
            script == Character.UnicodeScript.KATAKANA ||
            script == Character.UnicodeScript.HANGUL
    }

    private const val MIN_CASED = 2
    private const val UPPER_SHARE = 0.8
}
