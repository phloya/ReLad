package plus.translate

/**
 * Tells a model's refusal, written where its notes about the page go, from a note about a page
 * without text. Models refuse in the language they answer in: the reader's language or English.
 */
internal object Refusals {

    fun looksLike(note: String): Boolean = note.isNotBlank() && PATTERN.containsMatchIn(note)

    private val PATTERN = Regex(
        listOf(
            // Russian, in the first person: "Фарнезе отказывается уйти" is a note about the story
            "отказываюсь",
            "не (могу|буду|стану) (помочь|переводить|перевести|выполнить)",
            "не могу (с этим )?помочь",
            // English, the same way: "Guts refuses" is not a refusal
            "\\bi (must |have to |will )?(refuse|decline)\\b",
            "\\bi (can't|cannot|can not|won't|will not|am unable to|am not able to) (help|translate|assist|comply|provide)",
            "\\bi'm (not able|unable) to (help|translate|assist|comply|provide)",
        ).joinToString("|"),
        RegexOption.IGNORE_CASE,
    )
}
