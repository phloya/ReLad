package plus.translate

/** Builds the request to a multimodal model: find all text on the page and translate it. */
object TranslationPrompt {

    /** Bump when the prompt or the answer format changes: cached translations are then redone. */
    const val VERSION = 1

    fun system(language: TargetLanguage): String = """
        You are a professional manga translator and letterer.
        You get one manga, manhwa or manhua page. Find every piece of text on it:
        speech and thought bubbles, narration boxes, sound effects (including one-character ones)
        and any other text drawn on the art (signs, notes, handwriting).
        Translate each into ${language.promptName}. ${language.promptNotes}
        Translate meaning and tone, not word by word: natural, spoken style for dialogue,
        short and punchy for sound effects. Keep names consistent with the context below.
        Do not translate page numbers, watermarks or credits; leave them out.

        Answer with JSON only, no other text, in this format:
        {"regions":[{"type":"bubble","box":[ymin,xmin,ymax,xmax],"text":"original text","translation":"translated text"}]}
        - "type" is one of: "bubble", "caption", "sfx", "other".
        - "box" is the tight box around the text itself (for bubbles: around the text inside, not the
          whole bubble), as integers from 0 to 1000 relative to the image: y from the top edge,
          x from the left edge.
        - List regions in reading order. Japanese pages read right to left, top to bottom.
        - One bubble = one region, even if its text has several lines or columns.
        - If the page has no text, answer {"regions":[]}.
    """.trimIndent()

    fun user(previous: List<TextRegion>): String = buildString {
        append("Translate this page.")
        if (previous.isNotEmpty()) {
            append("\n\nContext: translations on the previous page(s) of this chapter, for consistency:\n")
            previous.takeLast(MAX_CONTEXT_REGIONS).forEach { region ->
                append("- ").append(region.text.replace('\n', ' ')).append(" → ")
                append(region.translation.replace('\n', ' ')).append('\n')
            }
        }
    }

    private const val MAX_CONTEXT_REGIONS = 30
}
