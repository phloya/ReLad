package plus.colorize

/** What the image model is asked. Changing the text changes [VERSION], so pages are colored again. */
object ColorizePrompt {
    const val VERSION = 1

    const val SYSTEM = "You color black-and-white manga and comic pages for readers."

    fun user(mangaTitle: String?, withReference: Boolean): String = buildString {
        appendLine("Color this black-and-white manga page.")
        appendLine()
        appendLine("Rules:")
        appendLine(
            "- Keep the drawing exactly as it is: every line, shape, panel border, speech bubble and letter " +
                "stays in the same place and size. Do not add, remove, move, redraw or retouch anything. " +
                "Do not crop, zoom or change the framing.",
        )
        appendLine(
            "- Only add color: natural, consistent colors for skin, hair, eyes, clothes, objects and backgrounds.",
        )
        appendLine("- Speech bubbles and caption boxes stay white, text stays black.")
        appendLine("- The white margins around the page stay white.")
        if (!mangaTitle.isNullOrBlank()) {
            appendLine("- The manga is \"$mangaTitle\". If you know its official colors for the characters, use them.")
        }
        if (withReference) {
            appendLine(
                "- The second image is an earlier page of the same chapter, already colored. " +
                    "Give the same characters and places the same colors.",
            )
        }
        appendLine()
        append("Return only the colored page as an image.")
    }
}

/**
 * Small copies of pages already colored in recent chapters, so the next page can be given one as
 * a reference and characters keep their colors. Memory only.
 */
object ChapterColors {

    private val chapters = object : LinkedHashMap<Long, MutableMap<Int, ByteArray>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, MutableMap<Int, ByteArray>>) =
            size > MAX_CHAPTERS
    }

    @Synchronized
    fun remember(chapterId: Long, pageIndex: Int, jpeg: ByteArray) {
        chapters.getOrPut(chapterId) { mutableMapOf() }[pageIndex] = jpeg
    }

    /** The colored page closest before [pageIndex] (or after it, if none before). */
    @Synchronized
    fun reference(chapterId: Long, pageIndex: Int): ByteArray? {
        val pages = chapters[chapterId] ?: return null
        val before = pages.keys.filter { it < pageIndex }.maxOrNull()
        val after = pages.keys.filter { it > pageIndex }.minOrNull()
        return (before ?: after)?.let { pages[it] }
    }

    private const val MAX_CHAPTERS = 10
}
