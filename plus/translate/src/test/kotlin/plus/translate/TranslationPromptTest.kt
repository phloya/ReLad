package plus.translate

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import plus.translate.bible.TitleBible

class TranslationPromptTest {

    private val russian = TargetLanguage.of("ru")

    @Test
    fun `rules are filled in and not indented`() {
        val rules = TranslationPrompt.rules(russian, "ja", translateSoundEffects = true)

        rules shouldStartWith
            "You are a professional translator and letterer of Japanese manga, translating into Russian."
        rules shouldNotContain "{STYLE}"
        rules shouldContain "\n- Translate what the line means"
        rules shouldContain "Names: Polivanov system"
        rules shouldContain "Japanese pages read right to left"
    }

    @Test
    fun `the source language picks the reading order and the name rules`() {
        val korean = TranslationPrompt.rules(russian, "ko", translateSoundEffects = false)

        korean shouldContain "Korean manhwa"
        korean shouldContain "Names: Kontsevich system"
        korean shouldContain "Sound effects: leave them out."
        korean shouldNotContain "Polivanov"
    }

    @Test
    fun `the page request has the last lines with speakers`() {
        val bible = TitleBible(1, "A", "ru")
        val previous = listOf(TextRegion(RegionType.Bubble, Box(0, 0, 9, 9), "行く", "Идём", speaker = "炭治郎"))

        TranslationPrompt.page(bible, 10, "Глава 1", 3, previous) shouldContain "- [炭治郎] 行く → Идём"
    }
}
