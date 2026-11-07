package plus.translate.bible

import java.text.Normalizer

/** What the model noticed on a page besides the translation itself. */
data class PageFindings(
    val characters: List<Character> = emptyList(),
    val terms: List<Term> = emptyList(),
    /** One or two sentences on what happens on the page. */
    val summary: String = "",
    /** Original text of every line on the page, to see which names and terms came up. */
    val lines: List<String> = emptyList(),
    /** Original names of the characters the model was sure spoke on the page. */
    val speakers: List<String> = emptyList(),
)

/**
 * Adds what the model found on a page to the title's notes. The first choice wins: a name or term
 * that is already there keeps its translation, so later pages can't drift. Only empty details
 * (gender, manner of speech...) of entries the reader hasn't locked are filled in.
 */
object BibleUpdates {

    fun apply(
        bible: TitleBible,
        findings: PageFindings,
        chapterId: Long,
        chapterName: String,
        pageIndex: Int,
    ): TitleBible {
        val merged = mergeCharacters(bible.characters, findings.characters, chapterName, chapterId)
        val characterKeys = merged.flatMap { it.keys() }.toSet()
        val mergedTerms = mergeTerms(bible.terms, findings.terms, characterKeys, chapterName, chapterId)

        // Who and what came up on this page stays in the prompt for the next chapters
        val text = findings.lines.joinToString("\n")
        val speakers = findings.speakers.map(::key).toSet()
        val characters = merged.map { character ->
            val names = listOf(character.original) + character.aliases.map { it.original }
            val seen = character.keys().any { it in speakers } || names.any { mentionsOriginal(text, it) }
            if (seen) character.copy(lastSeenChapter = chapterId) else character
        }
        val terms = mergedTerms.map { term ->
            if (mentionsOriginal(text, term.original)) term.copy(lastSeenChapter = chapterId) else term
        }
        val chapters = addPageSummary(bible.chapters, findings.summary, chapterId, chapterName, pageIndex)
        if (characters == bible.characters && terms == bible.terms && chapters == bible.chapters) return bible
        return bible.copy(characters = characters, terms = terms, chapters = chapters, revision = bible.revision + 1)
    }

    private fun mergeCharacters(
        known: List<Character>,
        found: List<Character>,
        chapterName: String,
        chapterId: Long,
    ): List<Character> {
        val result = known.toMutableList()
        for (candidate in found) {
            val clean = candidate.cleaned() ?: continue
            val index = result.indexOfFirst { existing -> existing.keys().any { it in clean.keys() } }
            if (index < 0) {
                if (result.size < MAX_CHARACTERS) {
                    result += clean.copy(locked = false, firstSeen = chapterName, lastSeenChapter = chapterId)
                }
                continue
            }
            val existing = result[index]
            if (existing.locked) continue
            val knownAliases = existing.aliases.map { key(it.original) }.toSet() + key(existing.original)
            result[index] = existing.copy(
                gender = if (existing.gender == Gender.Unknown) clean.gender else existing.gender,
                speech = existing.speech.ifBlank { clean.speech },
                addresses = existing.addresses.ifBlank { clean.addresses },
                notes = existing.notes.ifBlank { clean.notes },
                aliases = existing.aliases + clean.aliases.filter { key(it.original) !in knownAliases },
            )
        }
        return result
    }

    private fun mergeTerms(
        known: List<Term>,
        found: List<Term>,
        characterKeys: Set<String>,
        chapterName: String,
        chapterId: Long,
    ): List<Term> {
        val result = known.toMutableList()
        for (candidate in found) {
            val clean = candidate.cleaned() ?: continue
            val k = key(clean.original)
            if (k in characterKeys) continue
            val index = result.indexOfFirst { key(it.original) == k }
            if (index < 0) {
                if (result.size < MAX_TERMS) {
                    result += clean.copy(locked = false, firstSeen = chapterName, lastSeenChapter = chapterId)
                }
            } else if (!result[index].locked && result[index].note.isBlank() && clean.note.isNotBlank()) {
                result[index] = result[index].copy(note = clean.note)
            }
        }
        return result
    }

    private fun addPageSummary(
        chapters: List<ChapterNotes>,
        summary: String,
        chapterId: Long,
        chapterName: String,
        pageIndex: Int,
    ): List<ChapterNotes> {
        val text = summary.trim().take(MAX_PAGE_SUMMARY)
        if (text.isEmpty()) return chapters
        val existing = chapters.firstOrNull { it.chapterId == chapterId }
        val base = existing ?: ChapterNotes(chapterId, chapterName)
        val updated = base.copy(pages = base.pages + (pageIndex to text))
        if (updated == existing) return chapters
        val others = chapters.filter { it.chapterId != chapterId }
        // The chapter being read goes last: the order chapters were read in is the order of the story so far
        return (others + updated).takeLast(MAX_CHAPTERS)
    }

    private fun Character.cleaned(): Character? {
        val original = original.trim()
        val name = name.trim()
        if (original.isEmpty() || name.isEmpty() || original.length > MAX_NAME || name.length > MAX_NAME) return null
        return copy(
            original = original,
            name = name,
            aliases = aliases.mapNotNull { alias ->
                Alias(alias.original.trim(), alias.translation.trim()).takeIf {
                    it.original.isNotEmpty() && it.translation.isNotEmpty() && it.original.length <= MAX_NAME
                }
            },
            speech = speech.trim().take(MAX_DETAIL),
            addresses = addresses.trim().take(MAX_DETAIL),
            notes = notes.trim().take(MAX_DETAIL),
        )
    }

    private fun Term.cleaned(): Term? {
        val original = original.trim()
        val translation = translation.trim()
        val tooLong = original.length > MAX_NAME || translation.length > MAX_NAME
        if (original.isEmpty() || translation.isEmpty() || tooLong) return null
        return copy(original = original, translation = translation, note = note.trim().take(MAX_DETAIL))
    }

    /**
     * Whether the original [text] has the name or term [original]. Japanese, Korean and Chinese are
     * matched anywhere (no spaces between words); Latin and other scripts only as whole words, so
     * "Kai" is not found in "kaiju".
     */
    internal fun mentionsOriginal(text: String, original: String): Boolean {
        val wanted = key(original)
        if (wanted.length < MIN_MATCH) return false
        if (original.any(::isCjk)) return key(text).contains(wanted)
        val words = Normalizer.normalize(original, Normalizer.Form.NFKC).lowercase().trim()
            .split(Regex("\\s+")).joinToString("\\s+") { Regex.escape(it) }
        return Regex("(?<![\\p{L}\\p{N}])$words(?![\\p{L}\\p{N}])")
            .containsMatchIn(Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase())
    }

    /** Kana, Hangul and Han characters. */
    internal fun isCjk(c: Char): Boolean = c.code in 0x3040..0x30FF || // hiragana, katakana
        c.code in 0x3400..0x4DBF || // Han extension A
        c.code in 0x4E00..0x9FFF || // Han
        c.code in 0xAC00..0xD7AF || // Hangul syllables
        c.code in 0x1100..0x11FF || // Hangul jamo
        c.code in 0x3130..0x318F || // Hangul compatibility jamo
        c.code in 0xFF66..0xFF9F // half-width katakana

    /** All spellings a character is matched by. */
    internal fun Character.keys(): List<String> = (listOf(original) + aliases.map { it.original }).map(::key)

    /** Same width, case and spacing for matching: "Ｋａｉ" and "kai", "東 京" and "東京" match. */
    internal fun key(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()
            .filterNot { it.isWhitespace() || it == '・' || it == '·' }

    private const val MAX_CHARACTERS = 150
    private const val MAX_TERMS = 500
    private const val MAX_CHAPTERS = 300
    private const val MAX_NAME = 60
    private const val MAX_DETAIL = 300
    private const val MAX_PAGE_SUMMARY = 400

    /** Shorter keys ("ア", "王") match inside other words too often to count as seen. */
    private const val MIN_MATCH = 2
}
