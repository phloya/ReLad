package plus.translate.bible

import plus.translate.TextRegion

/**
 * Free checks of a translated page, done on the phone: lines where the model left the original
 * script, or used another word for a name or term than the title's notes say. Only these lines go
 * back to the model for a second look, which costs a small text request instead of a whole page.
 */
object TranslationChecks {

    data class Problem(val index: Int, val kind: Kind, val expected: String = "")

    enum class Kind {
        /** Japanese, Korean or Chinese characters left in a translation into another script. */
        Untranslated,

        /** A name or term from the notes is in the original but its chosen translation is not. */
        WrongName,
    }

    fun check(regions: List<TextRegion>, bible: TitleBible): List<Problem> {
        val keepsCjk = bible.language.substringBefore('-').lowercase() in CJK_LANGUAGES
        val names = expectedNames(bible)
        val problems = mutableListOf<Problem>()
        regions.forEachIndexed { index, region ->
            if (!keepsCjk && region.translation.any(BibleUpdates::isCjk)) {
                problems += Problem(index, Kind.Untranslated)
                return@forEachIndexed
            }
            names.firstOrNull { (source, target) ->
                BibleUpdates.mentionsOriginal(region.text, source) && !mentions(region.translation, target)
            }?.let { (_, target) -> problems += Problem(index, Kind.WrongName, target) }
        }
        return problems
    }

    /** original → wanted translation, for every name and term. */
    private fun expectedNames(bible: TitleBible): List<Pair<String, String>> {
        val characters = bible.characters.flatMap { character ->
            listOf(character.original to character.name) + character.aliases.map { it.original to it.translation }
        }
        val terms = bible.terms.map { it.original to it.translation }
        return (characters + terms)
            .filter { (_, target) -> target.isNotBlank() }
            // Longer originals first: "鬼殺隊士" is checked before "鬼殺隊"
            .sortedByDescending { BibleUpdates.key(it.first).length }
    }

    /**
     * Whether [translation] uses [expected], allowing for grammar endings: every word of the
     * expected form must appear with at least its stem ("Корпус истребителей" matches
     * "Корпусом истребителей").
     */
    internal fun mentions(translation: String, expected: String): Boolean {
        val text = translation.lowercase()
        val words = expected.lowercase().split(' ', '-', '‐').filter { it.isNotBlank() }
        return words.all { word ->
            val stem = if (word.length > STEM_FROM) word.take(maxOf(STEM_FROM, word.length - ENDING)) else word
            text.contains(stem)
        }
    }

    private val CJK_LANGUAGES = setOf("ja", "ko", "zh")

    /** Words longer than this may lose up to [ENDING] letters to grammar. */
    private const val STEM_FROM = 4
    private const val ENDING = 3
}
