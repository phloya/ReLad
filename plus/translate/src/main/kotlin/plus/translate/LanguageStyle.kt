package plus.translate

/**
 * A short style sheet for one target language: what publishers and good fan teams of that language
 * do. Kept short on purpose: research found that long lists of rules make LLM translations worse,
 * while a few examples help (see plus/TRANSLATION.md). Languages without a sheet get the general one.
 */
class LanguageStyle(
    private val general: String,
    /** How to write names from each source language, the biggest source of drift between chapters. */
    private val names: Map<SourceLanguage, String> = emptyMap(),
    private val examples: String = "",
) {
    fun notes(source: SourceLanguage): String = listOfNotNull(
        general.trim(),
        names[source]?.let { "Names: ${it.trim()}" },
        examples.trim().takeIf { it.isNotEmpty() }?.let { "Examples of the style wanted:\n$it" },
    ).joinToString("\n")

    companion object {
        fun of(code: String): LanguageStyle = sheets[code.substringBefore('-').lowercase()] ?: GENERAL

        private val GENERAL = LanguageStyle(
            general = """
                - Use natural, current spoken language in dialogue; keep narration a little more literary.
                - Honorifics and kinship titles: convey the relationship with the language's own means
                  (formal or informal "you", name forms) unless the reader asked to keep them.
            """.trimIndent(),
        )

        private val sheets: Map<String, LanguageStyle> = mapOf(
            "ru" to LanguageStyle(
                general = """
                    - "Ты" or "вы" for every pair of characters, from their relationship (-san alone does not mean
                      "вы": a teacher to a student is "ты"); keep it until the story changes it.
                    - Past tense and adjectives agree with the speaker's and listener's gender: check the Characters
                      list and the art.
                    - Honorifics: adapt them thoughtfully, don't keep them mechanically. Keep -кун, -тян, -сама,
                      сэмпай, сэнсэй where they carry the tone, with a hyphen (Сакура-тян); decline the name, not
                      the suffix (у Танаки-сан); -сан is usually better conveyed by "вы" and politeness.
                      Chinese titles in Palladius spelling: шисюн, шиди, шицзе, шимэй, шифу, шицзунь. Korean: хён,
                      оппа, нуна, онни, сонбэ, -ним where they carry the tone. An emperor's 朕 is just "я".
                    - Typography: «ёлочки»; exactly three dots or "…", and "?..", "!.."; "?!" with the question mark
                      first; one "!" or three, never two; a dash with spaces (—). A sentence split over two bubbles
                      ends the first with "…" and starts the second with "…". "Ё" where it prevents misreading.
                      Normal sentence case, not capitals. Numbers up to ten in words.
                    - Interjections the Russian way: "Eh?" / 「え？」 is «А?» or «Что?», not «Э?»; a cut-off word is
                      «Чт…».
                    - No calques, anglicisms or clumsy literal phrases; read it as Russian prose.
                    - Speaking names and attack names may be translated by meaning when the original plays on it.
                    - Sound effects: established Russian words (БАМ, БАХ, ГРОХ, ТУК-ТУК, ШУРХ); no invented
                      ones; a short word for a silent action (кивок); silence is «…». Sleep and snoring (Zzz,
                      ぐー) is «Хр-р…», never «Ззз»: in capitals it reads as the number 333.
                    - The same sound or word the same way on every page (one spelling: доккэби, not also докэби).
                """.trimIndent(),
                names = mapOf(
                    SourceLanguage.Japanese to "Polivanov system, as licensed Russian editions use it: し си, ち ти, " +
                        "つ цу, じ дзи, しゃ ся, ちゃ тя, じゃ дзя, え э, よ ё, ん н (м before б, п, м; нъ before a vowel); " +
                        "long vowels not marked (Сасукэ, Тидзуру, Сёта, Кэндзи). Words Russian already has stay as " +
                        "they are (суши, Токио).",
                    SourceLanguage.Korean to "Kontsevich system: given name one word (Мун Джэин, Сон Джину), " +
                        "its first consonant voiced after the surname; ㅓ and ㅗ о, ㅡ ы, ㅐ э; " +
                        "surnames Ким, Ли, Пак, Чхве, Ю, Син, Чон, Со.",
                    SourceLanguage.Chinese to "Palladius system: zh чж, j and z цз, q and c ц, x с, r ж, " +
                        "-n нь, -ng н, hui хуэй (Чжан, Сяо, Е Фань, Лин Мо); given name one word.",
                    SourceLanguage.English to "the English names are romanized: write Japanese names by Polivanov " +
                        "(Sasuke Uchiha → Сасукэ Утиха, Shikamaru → Сикамару), Korean by Kontsevich (Jinwoo → Джину, " +
                        "Seong → Сон), Chinese by Palladius (Xiao → Сяо), not by English letters.",
                ),
                examples = """
                    - 「お前、まだそんなこと言ってんのかよ」 → «Ты всё ещё несёшь эту чушь?»
                    - 「先輩、待ってくださいよ～！」 → «Сэмпай, ну подождите же!»
                    - 「え？」 → «А?»
                    - 「ドン！」 → «БАХ!»
                """.trimIndent(),
            ),
            "uk" to LanguageStyle(
                general = """
                    - Choose "ти" or "ви" for every pair of characters and keep it; past tense agrees with gender.
                    - Honorifics: as in Ukrainian editions, keep -кун, -тян, -сама, семпай, сенсей where they carry
                      the tone (Сакура-тян); drop -сан.
                    - Ukrainian typography: «ялинки» for quotes, the apostrophe ʼ in words like м'ята.
                """.trimIndent(),
                names = mapOf(
                    SourceLanguage.Japanese to "Kovalenko system (Ukrainian standard): し сі, ち ті, つ цу, じ дзі, え е " +
                        "(Сасуке, Тідзуру).",
                    SourceLanguage.Korean to "Ukrainian practice: 김 Кім, 이 Лі, 박 Пак (Сон Джину).",
                    SourceLanguage.Chinese to "Ukrainian Palladius-based practice: 张 Чжан, 李 Лі, 萧 Сяо.",
                ),
            ),
            "en" to LanguageStyle(
                general = """
                    - Natural American English as in official English editions; contractions in dialogue.
                    - Honorifics: drop or adapt by default (Mr., big sis, senior) unless the reader keeps them.
                    - Sound effects in English (BAM, CRACK, THUD); for silent actions a verb (nod, stare).
                """.trimIndent(),
                names = mapOf(
                    SourceLanguage.Japanese to "modified Hepburn without macrons, family name order as in the " +
                        "original unless the title uses Western order (Sasuke Uchiha or Uchiha Sasuke: keep one).",
                    SourceLanguage.Korean to "Revised Romanization, given names joined or hyphenated as the title " +
                        "established (Sung Jinwoo).",
                    SourceLanguage.Chinese to "pinyin without tone marks, given names joined (Ye Fan, Lin Mo).",
                ),
            ),
            "uz" to LanguageStyle(
                general = """
                    - Current Latin alphabet: oʻ and gʻ with ʻ (U+02BB), the tutuq belgisi ʼ (U+02BC) as in sanʼat;
                      never ‘ ’ or the plain apostrophe.
                    - Choose "sen" or "siz" for every pair of characters and keep it.
                    - Honorifics and kinship titles: Uzbek forms where they fit (aka, opa, ustoz, akajon);
                      keep -san, -kun, senpai where they carry the tone.
                    - Uzbek has no grammatical gender: make it clear from words when the original needs it.
                    - Every name in Uzbek Latin spelling as it sounds, never English spelling: no th, ph, w
                      (Thorfinn → Torfinn, Leif → Leyf, Phoebe → Fibi).
                """.trimIndent(),
                names = mapOf(
                    SourceLanguage.Japanese to
                        "Uzbek Latin spelling close to Hepburn: sh, ch, j, y, ts (Sasuke, Chizuru).",
                    SourceLanguage.Korean to "Uzbek Latin spelling of the Korean name (Son Jinu, Kim, Li, Pak).",
                    SourceLanguage.Chinese to "Uzbek Latin spelling close to pinyin read aloud (Ye Fan, Syao).",
                ),
            ),
            "kk" to LanguageStyle(
                general = """
                    - Modern spoken Kazakh, the way young readers talk; not a copy of Japanese intonation.
                    - Choose "сен" or "сіз" for every pair of characters and keep it.
                    - Honorifics and kinship titles: Kazakh forms where they fit (аға, апа, ұстаз); keep -сан, -кун,
                      сэмпай where they carry the tone.
                    - Kazakh has no grammatical gender: make it clear from words when the original needs it.
                """.trimIndent(),
                names = mapOf(
                    SourceLanguage.Japanese to "as in Russian practice (Polivanov system): Сасукэ, Тидзуру.",
                    SourceLanguage.Korean to "as in Russian practice (Kontsevich system): Ким, Ли, Пак, Сон Джину.",
                    SourceLanguage.Chinese to "as in Russian practice (Palladius system): Чжан, Ли, Сяо.",
                ),
            ),
        )
    }
}
