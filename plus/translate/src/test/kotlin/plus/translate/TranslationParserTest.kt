package plus.translate

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import plus.translate.bible.Alias
import plus.translate.bible.Gender
import plus.translate.bible.TermKind

class TranslationParserTest {

    @Test
    fun `reads regions and maps 0-1000 boxes to pixels`() {
        val answer = """
            ```json
            {"regions":[
              {"type":"bubble","box":[100,200,300,500],"text":"こんにちは","translation":"Привет"},
              {"type":"sfx","box":[800,400,900,600],"text":"ドン","translation":"БУМ"}
            ]}
            ```
        """.trimIndent()

        val regions = TranslationParser.parse(answer, pageWidth = 1000, pageHeight = 1500)

        regions shouldHaveSize 2
        regions[0].type shouldBe RegionType.Bubble
        regions[0].box shouldBe Box(left = 200, top = 150, right = 500, bottom = 450)
        regions[0].translation shouldBe "Привет"
        regions[1].type shouldBe RegionType.Sfx
    }

    @Test
    fun `accepts a bare array, other box keys and unknown types`() {
        val answer = """[{"type":"narration","box_2d":[0,0,100,100],"translation":"Утро"},
            {"type":"sign","bbox":[10,10,50,50],"translation":"Магазин"}]"""

        val regions = TranslationParser.parse(answer, 1000, 1000)

        regions.map { it.type } shouldBe listOf(RegionType.Caption, RegionType.Other)
    }

    @Test
    fun `fixes swapped corners and clamps out-of-range values`() {
        val answer = """{"regions":[{"type":"bubble","box":[300,500,100,-20],"translation":"x"}]}"""

        TranslationParser.parse(answer, 1000, 1000).single().box shouldBe Box(0, 100, 500, 300)
    }

    @Test
    fun `skips regions without translation or with a broken box`() {
        val answer = """{"regions":[
            {"type":"bubble","box":[1,1,100,100],"translation":"  "},
            {"type":"bubble","box":[1,1,100],"translation":"x"},
            {"type":"bubble","box":[1,1,1,1],"translation":"tiny"},
            {"type":"bubble","translation":"no box"}
        ]}"""

        TranslationParser.parse(answer, 1000, 1000).shouldBeEmpty()
    }

    @Test
    fun `empty page`() {
        TranslationParser.parse("""{"regions":[]}""", 1000, 1000).shouldBeEmpty()
    }

    @Test
    fun `no JSON is an error`() {
        shouldThrow<TranslationParser.ParseException> {
            TranslationParser.parse("Sorry, I can't help with that.", 1000, 1000)
        }
    }

    @Test
    fun `reads speakers, new names and terms and the page summary`() {
        val answer = """{"regions":[
            {"type":"bubble","box":[1,1,100,100],"speaker":"炭治郎","text":"行くぞ","translation":"Идём!"},
            {"type":"bubble","box":[1,1,100,100],"speaker":"unsure","text":"え？","translation":"А?"}
          ],
          "new_characters":[{"original":"炭治郎","name":"Тандзиро","gender":"male","speech":"вежлив",
            "aliases":[{"original":"炭治郎くん","translation":"Тандзиро-кун"}]},{"original":"","name":"x"}],
          "new_terms":[{"original":"鬼殺隊","translation":"Корпус истребителей демонов","kind":"Organization"},
            {"original":"呼吸","translation":"Дыхание","kind":"breathing"}],
          "summary":"Тандзиро зовёт Нэдзуко в путь."}"""

        val page = TranslationParser.parsePage(answer, 1000, 1000)

        page.regions.map { it.speaker } shouldBe listOf("炭治郎", null)
        page.findings.speakers shouldBe listOf("炭治郎")
        page.findings.lines shouldBe listOf("行くぞ", "え？")
        page.findings.characters.single().let {
            it.name shouldBe "Тандзиро"
            it.gender shouldBe Gender.Male
            it.aliases shouldBe listOf(Alias("炭治郎くん", "Тандзиро-кун"))
        }
        page.findings.terms.map { it.kind } shouldBe listOf(TermKind.Organization, TermKind.Other)
        page.findings.summary shouldBe "Тандзиро зовёт Нэдзуко в путь."
    }

    @Test
    fun `reads corrected lines from a second look`() {
        val answer = """{"fixes":[{"id":0,"translation":"Корпус"},{"id":"2","translation":" "},{"translation":"x"}]}"""

        TranslationParser.parseFixes(answer) shouldBe mapOf(0 to "Корпус")
    }
}
