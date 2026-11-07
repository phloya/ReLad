package plus.translate

/**
 * Spelling fixes done on the phone, free, where models are unreliable however clearly asked.
 * Uzbek: models mix the right letters oʻ, gʻ (U+02BB) and the tutuq belgisi ʼ (U+02BC) with quotes
 * and apostrophes (o‘, g', ta'qib); a live test got both on neighbouring pages.
 */
object Orthography {

    fun fix(languageCode: String, text: String): String = when (languageCode.substringBefore('-').lowercase()) {
        "uz" -> uzbek(text)
        else -> text
    }

    private fun uzbek(text: String): String {
        val out = StringBuilder(text.length)
        text.forEachIndexed { i, c ->
            val before = text.getOrNull(i - 1)
            val after = text.getOrNull(i + 1)
            val betweenLetters = before?.isLetter() == true && after?.isLetter() == true
            out.append(
                when {
                    c !in APOSTROPHES -> c
                    // oʻ and gʻ: the turned comma after o or g, also at a word's end ("koʻ")
                    before != null && before in "oOgG" && (after == null || !after.isLetter() || betweenLetters) &&
                        c != MODIFIER_APOSTROPHE -> TURNED_COMMA
                    betweenLetters -> MODIFIER_APOSTROPHE
                    else -> c
                },
            )
        }
        return out.toString()
    }

    private const val TURNED_COMMA = 'ʻ'
    private const val MODIFIER_APOSTROPHE = 'ʼ'
    private val APOSTROPHES = setOf('\'', '‘', '’', '`', 'ʻ', 'ʼ')
}
