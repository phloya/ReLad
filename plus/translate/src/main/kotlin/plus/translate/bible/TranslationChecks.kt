package plus.translate.bible

import plus.translate.RegionType
import plus.translate.TextRegion
import java.lang.Character.UnicodeScript

/**
 * Free checks of a translated page, done on the phone: lines where the model left the original
 * script, or used another word for a name or term than the title's notes say. Only these lines go
 * back to the model for a second look, which costs a small text request instead of a whole page.
 */
object TranslationChecks {

    /** [next]: for [Kind.SplitSentence], the line the sentence goes on in. */
    data class Problem(val index: Int, val kind: Kind, val expected: String = "", val next: Int? = null)

    enum class Kind {
        /** Japanese, Korean or Chinese characters left in a translation into another script. */
        Untranslated,

        /** A name or term from the notes is in the original but its chosen translation is not. */
        WrongName,

        /** A word half in one alphabet and half in another ("Кёнгgи"): a name the model didn't finish. */
        MixedScript,

        /**
         * The original breaks off without an end mark and goes on in the next bubble ("THE REAL ONE
         * GOT FIRED" / "BECAUSE THE FAKE IS ACTING UP"): the model often translates the parts as two
         * sentences that don't join, so the two go back together.
         */
        SplitSentence,
    }

    fun check(regions: List<TextRegion>, bible: TitleBible): List<Problem> {
        val keepsCjk = bible.language.substringBefore('-').lowercase() in CJK_LANGUAGES
        val names = expectedNames(bible)
        val problems = mutableListOf<Problem>()
        val cyrillic = bible.language.substringBefore('-').lowercase() in CYRILLIC_LANGUAGES
        regions.forEachIndexed { index, region ->
            val leftLatin = cyrillic && leftInLatin(region.text, region.translation)
            if ((!keepsCjk && region.translation.any(BibleUpdates::isCjk)) || leftLatin) {
                problems += Problem(index, Kind.Untranslated)
                return@forEachIndexed
            }
            if (WORD.findAll(region.translation).any { mixesScripts(it.value) }) {
                problems += Problem(index, Kind.MixedScript)
                return@forEachIndexed
            }
            names.firstOrNull { (source, target) ->
                BibleUpdates.mentionsOriginal(region.text, source) && !mentions(region.translation, target)
            }?.let { (_, target) -> problems += Problem(index, Kind.WrongName, target) }
        }
        regions.zipWithNext().forEachIndexed { index, (first, next) ->
            if (splitsSentence(first, next)) problems += Problem(index, Kind.SplitSentence, next = index + 1)
        }
        return problems
    }

    /**
     * Whether [first]'s original stops mid-sentence and [next] goes on with it: no end mark, only a
     * letter, digit or comma at the end. Latin-script originals only (English scans), where end marks
     * are reliable; Japanese and Korean bubbles often have none at all.
     */
    internal fun splitsSentence(first: TextRegion, next: TextRegion): Boolean {
        if (first.type !in SPEECH || next.type !in SPEECH) return false
        if (!latin(first.text) || !latin(next.text)) return false
        val last = first.text.trimEnd().lastOrNull() ?: return false
        return last.isLetterOrDigit() || last in SENTENCE_GOES_ON
    }

    private fun latin(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.length < MIN_SPLIT_LETTERS) return false
        return letters.count { UnicodeScript.of(it.code) == UnicodeScript.LATIN } * 10 >= letters.length * 9
    }

    private val SPEECH = setOf(RegionType.Bubble, RegionType.Caption)
    private const val SENTENCE_GOES_ON = ",;:"

    /** Shorter lines ("HM", "AH") are cries, not halves of a sentence. */
    private const val MIN_SPLIT_LETTERS = 3

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

    /** Letters of more than one alphabet in one word. */
    internal fun mixesScripts(word: String): Boolean {
        var latin = false
        var other = false
        for (c in word) {
            if (!c.isLetter()) continue
            when (UnicodeScript.of(c.code)) {
                UnicodeScript.LATIN -> latin = true
                UnicodeScript.CYRILLIC, UnicodeScript.GREEK -> other = true
                else -> Unit
            }
        }
        return latin && other
    }

    /**
     * A line from a Latin-script page copied, not translated, into a Cyrillic one: mostly Latin
     * letters and the same as the original. Short tags (OK, HP, S-rank) are left alone.
     */
    private fun leftInLatin(original: String, translation: String): Boolean {
        val latin = translation.count {
            it.isLetter() &&
                UnicodeScript.of(it.code) == UnicodeScript.LATIN
        }
        val letters = translation.count { it.isLetter() }
        if (latin < MIN_LATIN_LETTERS || latin * 2 < letters) return false
        val words = WORD.findAll(translation.lowercase()).map { it.value }.filter { it.length > 2 }.toSet()
        val source = WORD.findAll(original.lowercase()).map { it.value }.toSet()
        return words.isNotEmpty() && words.count { it in source } * 2 >= words.size
    }

    private val WORD = Regex("[\\p{L}\\p{M}]+")
    private val CJK_LANGUAGES = setOf("ja", "ko", "zh")
    private val CYRILLIC_LANGUAGES = setOf("ru", "uk", "be", "bg", "sr", "mk", "kk", "ky", "tg", "mn")

    /** Fewer Latin letters than this are tags and abbreviations, not a line left in English. */
    private const val MIN_LATIN_LETTERS = 8

    /** Words longer than this may lose up to [ENDING] letters to grammar. */
    private const val STEM_FROM = 4
    private const val ENDING = 3
}
