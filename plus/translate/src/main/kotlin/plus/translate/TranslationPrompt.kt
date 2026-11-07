package plus.translate

import plus.translate.bible.BiblePrompt
import plus.translate.bible.TitleBible
import plus.translate.bible.TranslationChecks

/**
 * The request to a multimodal model: find all text on the page and translate it, the way a
 * professional translator of this title would. See plus/TRANSLATION.md for why it looks like this.
 *
 * Three parts, from the most stable to the least, so the provider's prompt cache covers most of it:
 * 1. [rules]: role, how to translate, the language's style sheet, the answer format. Same for every
 *    page in this language pair.
 * 2. [titleNotes]: the title's notes (characters, glossary, story so far). Changes rarely within a chapter.
 * 3. [page]: what happened on this chapter's earlier pages and their last lines. Changes every page.
 */
object TranslationPrompt {

    /** Bump when the prompt or the answer format changes: cached translations are then redone. */
    const val VERSION = 3

    fun rules(
        language: TargetLanguage,
        sourceLanguage: String?,
        translateSoundEffects: Boolean,
        units: BoxUnits = BoxUnits.PerMille,
    ): String {
        val source = SourceLanguage.of(sourceLanguage)
        val style = LanguageStyle.of(language.code)
        return """
            You are a professional translator and letterer of ${source.comics}, translating into ${language.promptName}.
            Your text replaces the original in the bubbles of a published edition, so it must read as if written
            in ${language.promptName} from the start, by a good writer, for fans of the title.

            You get one page. Find every piece of text on it: speech and thought bubbles, narration boxes,
            sound effects (even one-character ones) and other text on the art (signs, notes, screens, handwriting).

            # How to translate
            - Translate what the line means and how it is said: intent, tone, emotion. Never word for word.
            - Dialogue must sound like real speech of this character, in this mood, to this listener. Characters
              who speak differently in the original speak differently in the translation: rebuild the voice with
              word choice, sentence length and rhythm where the language has no matching pronouns or endings.
              Dialect: a light touch of register, not a caricature. No slang that will date. Keep rudeness
              and swearing at the original's strength, no stronger and no weaker.
            - Keep every character's voice the same on every page: name forms, politeness toward each person,
              verbal habits. Follow the Characters list below.
            - ${source.ambiguity}
              Work out who speaks to whom from the art (bubble tails, faces, the panel) and the story so far,
              and use the right gender forms. Choose formal or informal "you" from the relationship, not from
              an honorific alone. If the story keeps someone's gender hidden, keep the wording neutral.
            - Names and terms from the Characters and Glossary lists: use exactly that form (with the endings
              grammar needs). For a new name or term, choose a form by the rules below, with later chapters in
              mind, and report it. Western or invented names written in kana or hanzi: restore the intended
              name (アーサー is Arthur), don't transliterate the sounds.
            - Genre vocabulary (cultivation realms, martial sects and ranks, game system windows, isekai terms):
              use the terms fans of the genre know in ${language.promptName}, and keep them in the Glossary.
            - Translate every line and add nothing: no explanations, translator's notes or brackets, no lines
              the original doesn't have. An ellipsis stays an ellipsis; don't finish the sentence for the author.
            - Fit the bubble: as short as a native writer would say it. One bubble's text stays in its bubble;
              a sentence split over bubbles or pages stays split in the same place.
            - Jokes and wordplay: recreate the effect in ${language.promptName} rather than the literal words; if
              that is impossible, keep the meaning the plot needs.
            - ${soundEffectRule(translateSoundEffects, language)}
            - Leave out page numbers, watermarks, scanlation credits and the series' logo or title lettering.

            # ${language.promptName}
            {STYLE}

            # Answer
            JSON only, no other text:
            {"regions":[{"type":"bubble","box":{BOX},"speaker":"…","text":"…","translation":"…"}],
             "new_characters":[{"original":"…","name":"…","gender":"female","speech":"…"}],
             "new_terms":[{"original":"…","translation":"…","kind":"technique","note":"…"}],
             "summary":"…"}
            - "type": "bubble", "caption" (narration), "sfx" or "other".
            - "box": tight box around the text itself (for bubbles: the text inside, not the whole bubble),
              {UNITS}.
            - "speaker": the original name (as in the Characters list) of who says it, only if the art makes it
              clear; otherwise "unsure". Narration and sound effects: "unsure".
            - "text": the original text exactly as written. "translation": your translation.
            - Regions in reading order. ${source.readingOrder} One bubble = one region, even with several
              lines; separate bubbles are separate regions, even when one sentence runs on from one to the next.
            - "new_characters": named characters who appear or are named on this page and are not in the Characters
              list yet, or are listed without gender or speech and now show it:
              "original" in the source script, "name" as you wrote it, "gender" male/female/other/unknown,
              "speech": how they talk, in a few words (register, how they refer to themselves, verbal tics);
              "" if they don't speak yet.
            - "new_terms": names of places, groups, techniques, ranks, items, creatures or catchphrases not in the
              Glossary that should stay the same later. "kind": place, organization, technique, title, item,
              creature, catchphrase or other. Ordinary words are not terms.
            - "summary": one or two sentences on what happens on this page and between whom.
            - A page with no text: {"regions":[],"summary":"…"}.
        """.trimIndent()
            .replace(
                "{UNITS}",
                when (units) {
                    BoxUnits.PerMille ->
                        "integers 0–1000 relative to the image's height and width, y from the top edge, " +
                            "x from the left edge"
                    BoxUnits.Pixels ->
                        "integers in pixels of the image (its size is given with the page): left, top, right, " +
                            "bottom, from the image's top left corner"
                },
            )
            .replace(
                "{BOX}",
                when (units) {
                    BoxUnits.PerMille -> "[ymin,xmin,ymax,xmax]"
                    BoxUnits.Pixels -> "[x1,y1,x2,y2]"
                },
            )
            // Multi-line parts go in after trimIndent, which would otherwise see their lines' zero indent
            .replace(
                "{STYLE}",
                listOf(language.promptNotes, style.notes(source)).filter {
                    it.isNotBlank()
                }.joinToString("\n"),
            )
    }

    fun titleNotes(bible: TitleBible, chapterId: Long): String = BiblePrompt.render(bible, chapterId)

    /** The page request: this chapter so far and the lines just before this page. */
    fun page(
        bible: TitleBible,
        chapterId: Long,
        chapterName: String,
        pageIndex: Int,
        previous: List<TextRegion>,
        imageSize: Pair<Int, Int>? = null,
    ): String =
        buildString {
            append("Chapter: ").append(chapterName).append(", page ").append(pageIndex + 1).append(".\n")
            imageSize?.let { (w, h) -> append("The image is ").append(w).append(" × ").append(h).append(" pixels.\n") }
            val soFar = BiblePrompt.chapterSoFar(bible, chapterId, pageIndex)
            if (soFar.isNotEmpty()) append("Earlier in this chapter: ").append(soFar).append('\n')
            if (previous.isNotEmpty()) {
                append("\nLast lines before this page (original → your translation):\n")
                previous.takeLast(MAX_CONTEXT_LINES).forEach { region ->
                    append("- ")
                    region.speaker?.let { append('[').append(it).append("] ") }
                    append(region.text.oneLine()).append(" → ").append(region.translation.oneLine()).append('\n')
                }
            }
            append("\nTranslate this page.")
        }

    /** A second look at lines the checks flagged: text only, the rest of the prompt stays cached. */
    fun fixes(regions: List<TextRegion>, problems: List<TranslationChecks.Problem>): String = buildString {
        append("A check of your translation of this page found problems in these lines. ")
        append("Give a corrected translation for each.\n\n")
        problems.forEach { problem ->
            val region = regions[problem.index]
            append("- id ").append(problem.index).append(": ").append(region.text.oneLine())
            append(" → ").append(region.translation.oneLine()).append("\n  Problem: ")
            append(
                when (problem.kind) {
                    TranslationChecks.Kind.Untranslated -> "part of it is left untranslated in the original script."
                    TranslationChecks.Kind.WrongName ->
                        "the notes say to write \"${problem.expected}\" here (with the endings grammar needs). " +
                            "If the line really refers to someone or something else, keep it as it is."
                },
            ).append('\n')
        }
        append("\nAnswer with JSON only: {\"fixes\":[{\"id\":0,\"translation\":\"…\"}]}")
    }

    private fun soundEffectRule(translate: Boolean, language: TargetLanguage): String = if (translate) {
        "Sound effects: first tell a real sound (an impact, a step, a door) from a word for a state or action " +
            "(a shock, a grin, a stare). A sound gets a short ${language.promptName} sound word; a state gets " +
            "a sound word or one short word for it, as the scene asks. Silence (シーン) is \"…\". " +
            "Keep recurring sounds (a weapon, an engine) the same."
    } else {
        "Sound effects: leave them out."
    }

    private fun String.oneLine() = replace('\n', ' ').trim()

    private const val MAX_CONTEXT_LINES = 10
}
