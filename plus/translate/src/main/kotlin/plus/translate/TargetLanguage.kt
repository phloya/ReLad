package plus.translate

/**
 * A language to translate into. Any language a model knows works; this list only adds a name
 * for the settings screen and notes for the prompt where the script needs care.
 */
data class TargetLanguage(
    val code: String,
    /** Name in the language itself, for the settings list. */
    val nativeName: String,
    /** Name the model understands best. */
    val promptName: String,
    val promptNotes: String = "",
) {
    companion object {
        val all: List<TargetLanguage> = listOf(
            TargetLanguage("ru", "Русский", "Russian"),
            TargetLanguage("en", "English", "English"),
            TargetLanguage(
                "uz",
                "Oʻzbekcha",
                "Uzbek (Latin script)",
            ),
            TargetLanguage(
                "kk",
                "Қазақша",
                "Kazakh (Cyrillic script)",
                "Use the Kazakh Cyrillic alphabet with its own letters (ә, ғ, қ, ң, ө, ұ, ү, һ, і).",
            ),
            TargetLanguage("uk", "Українська", "Ukrainian"),
            TargetLanguage("tr", "Türkçe", "Turkish"),
            TargetLanguage("es", "Español", "Spanish"),
            TargetLanguage("pt-BR", "Português (Brasil)", "Brazilian Portuguese"),
            TargetLanguage("fr", "Français", "French"),
            TargetLanguage("de", "Deutsch", "German"),
            TargetLanguage("it", "Italiano", "Italian"),
            TargetLanguage("pl", "Polski", "Polish"),
            TargetLanguage("id", "Bahasa Indonesia", "Indonesian"),
            TargetLanguage("vi", "Tiếng Việt", "Vietnamese"),
            TargetLanguage("th", "ไทย", "Thai"),
            TargetLanguage("ar", "العربية", "Arabic"),
            TargetLanguage("ja", "日本語", "Japanese"),
            TargetLanguage("ko", "한국어", "Korean"),
            TargetLanguage("zh-Hans", "简体中文", "Simplified Chinese"),
            TargetLanguage("zh-Hant", "繁體中文", "Traditional Chinese"),
        )

        fun of(code: String): TargetLanguage = all.firstOrNull { it.code.equals(code, ignoreCase = true) }
            // Unknown code: let the model figure it out from the code itself
            ?: TargetLanguage(code, code, "the language with the code \"$code\"")
    }
}
