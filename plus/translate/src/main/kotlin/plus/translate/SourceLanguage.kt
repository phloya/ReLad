package plus.translate

/**
 * What the translator should know about the language a title comes from. Mihon knows the
 * source's language ("ja", "ko", "zh"...); sources marked "all" or unknown get the general case.
 */
enum class SourceLanguage(
    /** What the title is, for the model's role. */
    val comics: String,
    val readingOrder: String,
    /** What is hidden in this language and must be worked out from the art and the story. */
    val ambiguity: String,
) {
    Japanese(
        comics = "Japanese manga",
        readingOrder = "Japanese pages read right to left: panels from the top right corner, bubbles right to left, " +
            "top to bottom.",
        ambiguity = "Japanese often drops the subject and the object and rarely shows gender or number; " +
            "speech style (first-person pronoun, sentence endings, keigo) shows who the speaker is.",
    ),
    Korean(
        comics = "Korean manhwa (often vertical webtoon strips)",
        readingOrder = "Read top to bottom; panels side by side read left to right.",
        ambiguity = "Korean often drops the subject; speech levels (jondaetmal/banmal) and kinship and title words " +
            "(hyung, oppa, noona, unni, sunbae) show the relationship between speakers.",
    ),
    Chinese(
        comics = "Chinese manhua (often vertical strips)",
        readingOrder = "Usually left to right and top to bottom, vertical strips top to bottom; some editions " +
            "read right to left like manga: follow the layout.",
        ambiguity = "Chinese often drops the subject, and spoken he/she sound the same; ranks and kinship " +
            "titles (shixiong, shizun, dage, gongzi) show relationships.",
    ),

    /** English scanlations, very common in Mihon: a translation of a translation. */
    English(
        comics = "comics translated into English (manga, manhwa or manhua)",
        readingOrder = "Follow the layout: right to left for Japanese manga, otherwise left to right; top to bottom.",
        ambiguity = "The English text is itself a translation: names are romanized (Hepburn for Japanese, Korean " +
            "or pinyin romanization). Write names as they would be written from the original language when you " +
            "can tell it (a Korean name by the rules for Korean names), not by English spelling.",
    ),
    Other(
        comics = "comics (manga, manhwa or manhua)",
        readingOrder = "Follow the layout: right to left for Japanese manga, otherwise left to right; top to bottom.",
        ambiguity = "The original may drop subjects or not show gender.",
    ),
    ;

    companion object {
        fun of(code: String?): SourceLanguage = when (code?.substringBefore('-')?.lowercase()) {
            "ja" -> Japanese
            "ko" -> Korean
            "zh" -> Chinese
            "en" -> English
            else -> Other
        }
    }
}
