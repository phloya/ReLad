package plus.translate.bible

/**
 * Writes a title's notes as text for the model. The order of entries never changes between pages
 * (new ones go at the end), so the provider's prompt cache keeps working while a chapter is read.
 */
object BiblePrompt {

    fun render(bible: TitleBible, currentChapterId: Long): String = buildString {
        append("# This title\n")
        append("Title: ").append(bible.mangaTitle).append('\n')
        if (bible.about.genres.isNotEmpty()) {
            append("Genres: ").append(bible.about.genres.joinToString(", ")).append('\n')
        }
        if (bible.about.setting.isNotBlank()) {
            append("Setting and tone: ").append(bible.about.setting.trim()).append('\n')
        }
        if (bible.about.synopsis.isNotBlank()) {
            append("About: ").append(bible.about.synopsis.trim().shortened(MAX_SYNOPSIS)).append('\n')
        }

        when (bible.style.honorifics) {
            Honorifics.LanguageDefault -> Unit
            Honorifics.Keep -> append(
                "\nThe reader wants honorifics and kinship terms kept, transliterated " +
                    "(-san, -kun, senpai, hyung, shixiong...).\n",
            )
            Honorifics.Adapt -> append(
                "\nThe reader wants honorifics conveyed by the language's own means, not kept as foreign words.\n",
            )
        }
        if (bible.style.notes.isNotBlank()) {
            append("\nThe reader's wishes for this title: ").append(bible.style.notes.trim()).append('\n')
        }

        // Long series: only what the reader confirmed and what came up lately (old minor names only distract)
        val recent = bible.chapters.takeLast(RECENT_CHAPTERS).map { it.chapterId }.toSet() + currentChapterId
        val characters = bible.characters
            .relevant(MAX_CHARACTERS, recent, { it.locked }, { it.lastSeenChapter })
        if (characters.isNotEmpty()) {
            append("\n# Characters\n")
            append("Use exactly these names, genders and voices. original → translation.\n")
            characters.forEach { append("- ").append(it.line()).append('\n') }
        } else if (bible.characters.isEmpty()) {
            // With no list in sight models took "not in the list yet" for "nothing to report", and
            // pages translated side by side then spelled one name three ways
            append("\n# Characters\n")
            append("None yet: report every named character in \"new_characters\", ")
            append("so later pages spell them the same.\n")
        }

        val terms = bible.terms.relevant(MAX_TERMS, recent, { it.locked }, { it.lastSeenChapter })
        if (terms.isNotEmpty()) {
            append("\n# Glossary\n")
            append("Always translate these the same way (inflect them as the grammar needs). original → translation.\n")
            terms.forEach { term ->
                append("- ").append(term.original).append(" → ").append(term.translation)
                append(" (").append(term.kind.name.lowercase()).append(')')
                if (term.note.isNotBlank()) append(": ").append(term.note)
                append('\n')
            }
        }

        val story = bible.chapters
            .filter { it.chapterId != currentChapterId && it.pages.isNotEmpty() }
            .takeLast(STORY_CHAPTERS)
        if (story.isNotEmpty()) {
            append("\n# Story so far (previous chapters)\n")
            story.forEach { chapter ->
                append("- ").append(chapter.chapterName).append(": ")
                append(chapter.summary(MAX_CHAPTER_SUMMARY)).append('\n')
            }
        }
    }.trimEnd()

    /** This chapter's pages before [pageIndex], for the page request itself (changes every page). */
    fun chapterSoFar(bible: TitleBible, chapterId: Long, pageIndex: Int): String {
        val chapter = bible.chapters.firstOrNull { it.chapterId == chapterId } ?: return ""
        return chapter.pages.filterKeys { it < pageIndex }.toSortedMap().values
            .joinToString(" ") { it.trim() }
            .let { text -> if (text.length <= MAX_CHAPTER_SO_FAR) text else "…" + text.lastPart(MAX_CHAPTER_SO_FAR) }
    }

    private fun Character.line(): String = buildString {
        append(original).append(" → ").append(name)
        if (gender != Gender.Unknown) append(" (").append(gender.name.lowercase()).append(')')
        if (locked) append(" [confirmed by the reader]")
        if (aliases.isNotEmpty()) {
            append("; also ").append(aliases.joinToString(", ") { "${it.original} → ${it.translation}" })
        }
        if (speech.isNotBlank()) append("; speech: ").append(speech)
        if (addresses.isNotBlank()) append("; addresses others: ").append(addresses)
        if (notes.isNotBlank()) append("; ").append(notes)
    }

    private fun <T> List<T>.relevant(
        max: Int,
        recent: Set<Long>,
        keep: (T) -> Boolean,
        lastSeen: (T) -> Long,
    ): List<T> {
        if (size <= max) return this
        return filter { keep(it) || lastSeen(it) in recent }.limited(max, keep)
    }

    /** Keeps the list's order; when too long, drops the oldest entries the reader didn't confirm. */
    private fun <T> List<T>.limited(max: Int, keep: (T) -> Boolean): List<T> {
        if (size <= max) return this
        val kept = count(keep)
        var others = (max - kept).coerceAtLeast(0)
        val skip = (size - kept) - others
        var skipped = 0
        return filter { item ->
            when {
                keep(item) -> true
                skipped < skip -> {
                    skipped++
                    false
                }
                others > 0 -> {
                    others--
                    true
                }
                else -> false
            }
        }
    }

    /** The end of the text, from a word start. */
    private fun String.lastPart(max: Int) = takeLast(max).substringAfter(' ')

    private fun String.shortened(max: Int) = if (length <= max) this else take(max).substringBeforeLast(' ') + "…"

    private const val MAX_SYNOPSIS = 700
    private const val MAX_CHARACTERS = 40
    private const val MAX_TERMS = 150
    private const val RECENT_CHAPTERS = 5
    private const val STORY_CHAPTERS = 3
    private const val MAX_CHAPTER_SUMMARY = 700
    private const val MAX_CHAPTER_SO_FAR = 1200
}
