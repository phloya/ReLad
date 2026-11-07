package plus.translate

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import plus.core.ai.AiClient
import plus.core.ai.AiEffort
import plus.core.ai.AiImage
import plus.core.ai.AiRequest
import plus.core.ai.AiResponse
import plus.core.ai.AiUsage
import plus.core.pipeline.PageContext
import plus.translate.bible.BibleStore
import java.io.File

/** The whole text path on two pages of a chapter, with a model that answers from a script. */
class PageTextTranslatorTest {

    @TempDir
    lateinit var dir: File

    private class ScriptedModel(private val answers: MutableList<String>) : AiClient {
        val requests = mutableListOf<AiRequest>()
        override suspend fun send(request: AiRequest): AiResponse {
            requests += request
            return AiResponse(answers.removeAt(0), emptyList(), AiUsage(inputTokens = 1000, outputTokens = 100))
        }
    }

    private val image = PageTextTranslator.ModelImage(AiImage(byteArrayOf(1, 2, 3), "image/png"), 1000, 1500)

    private fun context(page: Int, chapterId: Long = 7001) = PageContext(
        mangaId = 1,
        mangaTitle = "Клинок",
        chapterId = chapterId,
        chapterName = "Глава 1",
        pageIndex = page,
        sourceLanguage = "ja",
        mangaDescription = "Тандзиро становится охотником на демонов.",
        mangaGenres = listOf("Action"),
    )

    private val firstPage = """{"regions":[{"type":"bubble","box":[100,100,200,300],"speaker":"炭治郎",
        "text":"鬼殺隊に入る！","translation":"Я вступлю в Корпус истребителей демонов!"}],
        "new_characters":[{"original":"炭治郎","name":"Тандзиро","gender":"male","speech":"вежлив, упрям"}],
        "new_terms":[{"original":"鬼殺隊","translation":"Корпус истребителей демонов","kind":"organization"}],
        "summary":"Тандзиро решает вступить в Корпус."}"""

    // On the next page the model drifts to another name for the same group
    private val secondPage = """{"regions":[{"type":"bubble","box":[100,100,200,300],"speaker":"unsure",
        "text":"鬼殺隊は甘くない","translation":"В Отряде охотников тебе не будет легко."}],
        "summary":"Учитель предупреждает Тандзиро."}"""
    private val fix = """{"fixes":[{"id":0,"translation":"В Корпусе истребителей демонов тебе не будет легко."}]}"""

    @Test
    fun `the second page gets the first page's names, and a drifted term is caught and fixed`() = runTest {
        val bibles = BibleStore(dir)
        val model = ScriptedModel(mutableListOf(firstPage, secondPage, fix))
        val translator = PageTextTranslator(bibles)
        val russian = TargetLanguage.of("ru")

        val one = translator.translate(model, image, 1000, 1500, context(0), russian, translateSoundEffects = true)
        val two = translator.translate(model, image, 1000, 1500, context(1), russian, translateSoundEffects = true)

        // Page 1: the rules are the cached part, the title's notes have only what the source says
        val first = model.requests[0]
        first.stableSystem!! shouldContain "Polivanov"
        first.system!! shouldContain "Genres: Action"
        first.system!! shouldContain "# Characters\nNone yet"
        first.images shouldHaveSize 1
        // Some hidden reasoning: without it text goes missing on long strips
        first.effort shouldBe AiEffort.Low
        one.regions.single().speaker shouldBe "炭治郎"

        // Page 2: same rules, the notes now carry the name and the term, the prompt the previous lines
        val second = model.requests[1]
        second.stableSystem shouldBe first.stableSystem
        second.system!! shouldContain "- 炭治郎 → Тандзиро (male); speech: вежлив, упрям"
        second.system!! shouldContain "- 鬼殺隊 → Корпус истребителей демонов (organization)"
        second.prompt shouldContain "Earlier in this chapter: Тандзиро решает вступить в Корпус."
        second.prompt shouldContain "- [炭治郎] 鬼殺隊に入る！ → Я вступлю в Корпус истребителей демонов!"

        // The check found the other name; one text-only request fixed that line
        val secondLook = model.requests[2]
        secondLook.images shouldHaveSize 0
        secondLook.stableSystem shouldBe first.stableSystem
        secondLook.prompt shouldContain "the notes say to write \"Корпус истребителей демонов\""
        two.regions.single().translation shouldBe "В Корпусе истребителей демонов тебе не будет легко."
        two.fixedLines shouldBe 1
        two.usage!!.inputTokens shouldBe 2000
        two.usage!!.outputTokens shouldBe 200

        // The notes are on disk for the next chapters, with the story so far
        val saved = BibleStore(dir).get(1, "Клинок", "ru", "ja")
        saved.characters.single().name shouldBe "Тандзиро"
        saved.terms.single().translation shouldBe "Корпус истребителей демонов"
        saved.chapters.single().summary() shouldBe "Тандзиро решает вступить в Корпус. Учитель предупреждает Тандзиро."
        saved.about.synopsis shouldBe "Тандзиро становится охотником на демонов."
    }

    @Test
    fun `a failed second look keeps the first translation`() = runTest {
        val bibles = BibleStore(dir)
        // Notes already know the term; the model drifts and then sends something unreadable
        val model = ScriptedModel(mutableListOf(firstPage, secondPage, "sorry, no JSON here"))
        val translator = PageTextTranslator(bibles)
        val russian = TargetLanguage.of("ru")

        translator.translate(model, image, 1000, 1500, context(0, chapterId = 7002), russian, true)
        val two = translator.translate(model, image, 1000, 1500, context(1, chapterId = 7002), russian, true)

        two.regions.single().translation shouldBe "В Отряде охотников тебе не будет легко."
    }
}
