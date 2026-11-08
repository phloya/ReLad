package plus.translate.bible

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Everything a translator of one title keeps in their notes, for one target language: what the
 * title is, who speaks how, which words were chosen for names and terms, and what happened so far.
 * It goes to the model with every page, so chapter 40 uses the same names and voices as chapter 1.
 *
 * The model proposes new characters and terms as it meets them ([BibleUpdates]); the first choice
 * stays unless the reader changes it. Entries the reader edited are [Character.locked] /
 * [Term.locked] and are never changed by the model.
 */
@Serializable
data class TitleBible(
    val mangaId: Long,
    val mangaTitle: String,
    /** Target language code, as in [plus.translate.TargetLanguage.code]. */
    val language: String,
    val sourceLanguage: String? = null,
    val about: TitleCard = TitleCard(),
    val style: StyleChoices = StyleChoices(),
    val characters: List<Character> = emptyList(),
    val terms: List<Term> = emptyList(),
    val chapters: List<ChapterNotes> = emptyList(),
    /** Grows with every change; lets the screen and the prompt cache notice changes cheaply. */
    val revision: Int = 0,
) {
    /**
     * Nothing learned yet: no characters, no terms, no page done. Until then a chapter's pages wait
     * for the page before (the first page settles the names); after it they go side by side.
     */
    val isNew: Boolean get() = characters.isEmpty() && terms.isEmpty() && chapters.isEmpty()
}

/** What the title is, from the source's description and the model's first pages. */
@Serializable
data class TitleCard(
    val genres: List<String> = emptyList(),
    val synopsis: String = "",
    /** Short notes the model or reader adds: setting, era, tone ("modern Tokyo high school, light comedy"). */
    val setting: String = "",
)

/** Choices the reader can change for this title; defaults come from the language's style notes. */
@Serializable
data class StyleChoices(
    val honorifics: Honorifics = Honorifics.LanguageDefault,
    /** Free text from the reader, passed to the model as is ("keep 'senpai'", "no swearing"). */
    val notes: String = "",
)

@Serializable
enum class Honorifics {
    /** What the language's style notes say (see [plus.translate.LanguageStyle]). */
    @SerialName("default")
    LanguageDefault,

    /** Keep -san, -kun, senpai, hyung, shixiong... transliterated. */
    @SerialName("keep")
    Keep,

    /** Convey them with the language's own means (formal/informal "you", name forms, words like "brother"). */
    @SerialName("adapt")
    Adapt,
}

@Serializable
data class Character(
    /** Name as written in the source (漢字, 한글, 汉字 or Latin). The key for matching. */
    val original: String,
    /** Name in the target language, exactly as it should appear. */
    val name: String,
    /** Other ways they are called: nicknames, titles, short names, each with its translation. */
    val aliases: List<Alias> = emptyList(),
    val gender: Gender = Gender.Unknown,
    /**
     * How they speak and how to render it: register, self-reference (ore/boku/watashi),
     * verbal tics, dialect ("rough, calls himself 'ore', ends sentences with ~ze → short blunt phrases").
     */
    val speech: String = "",
    /** How they address particular people ("Haruto: informal 'you', calls him 'Haru'"). */
    val addresses: String = "",
    val notes: String = "",
    /** Set when the reader edited the entry: the model then never changes it. */
    val locked: Boolean = false,
    /** Chapter where the model first met them, for the editor. */
    val firstSeen: String = "",
    /** Last chapter they spoke or were named in: only recent and confirmed entries go to the model. */
    val lastSeenChapter: Long = 0,
)

@Serializable
data class Alias(val original: String, val translation: String)

@Serializable
enum class Gender {
    @SerialName("male")
    Male,

    @SerialName("female")
    Female,

    @SerialName("other")
    Other,

    @SerialName("unknown")
    Unknown,
}

/** A word that must be translated the same way everywhere: places, techniques, ranks, items... */
@Serializable
data class Term(
    val original: String,
    val translation: String,
    val kind: TermKind = TermKind.Other,
    val note: String = "",
    val locked: Boolean = false,
    val firstSeen: String = "",
    val lastSeenChapter: Long = 0,
)

@Serializable
enum class TermKind {
    @SerialName("place")
    Place,

    @SerialName("organization")
    Organization,

    @SerialName("technique")
    Technique,

    @SerialName("title")
    Title,

    @SerialName("item")
    Item,

    @SerialName("creature")
    Creature,

    @SerialName("catchphrase")
    Catchphrase,

    @SerialName("other")
    Other,
}

/** What happened in one chapter: a line per translated page, put together in reading order. */
@Serializable
data class ChapterNotes(
    val chapterId: Long,
    val chapterName: String,
    /** Page index → one or two sentences on what happens on that page. */
    val pages: Map<Int, String> = emptyMap(),
) {
    /** The chapter in a few sentences, pages in order. */
    fun summary(maxChars: Int = Int.MAX_VALUE): String {
        val text = pages.toSortedMap().values.joinToString(" ") { it.trim() }
        return if (text.length <= maxChars) text else text.take(maxChars).substringBeforeLast(' ') + "…"
    }
}
