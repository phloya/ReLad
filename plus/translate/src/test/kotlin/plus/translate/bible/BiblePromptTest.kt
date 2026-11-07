package plus.translate.bible

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class BiblePromptTest {

    private val bible = TitleBible(
        mangaId = 1,
        mangaTitle = "Клинок, рассекающий демонов",
        language = "ru",
        sourceLanguage = "ja",
        about = TitleCard(genres = listOf("Action", "Historical"), synopsis = "Тандзиро становится охотником."),
        characters = listOf(
            Character("炭治郎", "Тандзиро", gender = Gender.Male, speech = "вежлив, говорит прямо"),
            Character("禰豆子", "Нэдзуко", gender = Gender.Female, locked = true),
        ),
        terms = listOf(Term("鬼殺隊", "Корпус истребителей демонов", TermKind.Organization)),
        chapters = listOf(
            ChapterNotes(10, "Глава 1", mapOf(0 to "Семью Тандзиро убили.", 1 to "Нэдзуко стала демоном.")),
            ChapterNotes(11, "Глава 2", mapOf(0 to "Встреча с Томиокой.", 1 to "Тандзиро уходит учиться.")),
        ),
    )

    @Test
    fun `notes list the title, characters, glossary and earlier chapters`() {
        val text = BiblePrompt.render(bible, currentChapterId = 11)

        text shouldContain "Title: Клинок, рассекающий демонов"
        text shouldContain "Genres: Action, Historical"
        text shouldContain "- 炭治郎 → Тандзиро (male); speech: вежлив, говорит прямо"
        text shouldContain "- 禰豆子 → Нэдзуко (female) [confirmed by the reader]"
        text shouldContain "- 鬼殺隊 → Корпус истребителей демонов (organization)"
        text shouldContain "- Глава 1: Семью Тандзиро убили. Нэдзуко стала демоном."
        // The chapter being read is told page by page in the page request instead
        text shouldNotContain "Встреча с Томиокой"
    }

    @Test
    fun `the chapter so far has only the pages before this one`() {
        BiblePrompt.chapterSoFar(bible, chapterId = 11, pageIndex = 1) shouldBe "Встреча с Томиокой."
        BiblePrompt.chapterSoFar(bible, chapterId = 11, pageIndex = 0) shouldBe ""
    }

    @Test
    fun `a long series sends confirmed and recently seen entries only, in their usual order`() {
        val many = (1..200).map { Term("術$it", "Техника $it", lastSeenChapter = if (it > 195) 99 else 1) } +
            Term("呼吸", "Дыхание", locked = true)
        val chapters = (90L..99L).map { ChapterNotes(it, "Глава $it", mapOf(0 to "…")) }
        val text = BiblePrompt.render(bible.copy(terms = many, chapters = chapters), currentChapterId = 99)

        val listed = text.lines().filter { it.startsWith("- 術") || it.startsWith("- 呼吸") }
        listed.map { it.substringBefore(" →").removePrefix("- ") } shouldBe
            listOf("術196", "術197", "術198", "術199", "術200", "呼吸")
    }

    @Test
    fun `the same notes give the same text, so the prompt cache keeps working`() {
        BiblePrompt.render(bible, 11) shouldBe BiblePrompt.render(bible.copy(revision = 5), 11)
    }
}
