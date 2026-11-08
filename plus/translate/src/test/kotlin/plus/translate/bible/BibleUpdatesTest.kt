package plus.translate.bible

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BibleUpdatesTest {

    private val empty = TitleBible(mangaId = 1, mangaTitle = "Test", language = "ru")

    private fun page(vararg characters: Character, terms: List<Term> = emptyList(), summary: String = "") =
        PageFindings(characters = characters.toList(), terms = terms, summary = summary)

    @Test
    fun `the first translation of a name stays`() {
        val first = BibleUpdates.apply(empty, page(Character("炭治郎", "Тандзиро")), 10, "Глава 1", 0)
        val later = BibleUpdates.apply(first, page(Character("炭治郎", "Танджиро")), 11, "Глава 2", 3)

        later.characters.single().name shouldBe "Тандзиро"
        later.characters.single().firstSeen shouldBe "Глава 1"
    }

    @Test
    fun `empty details are filled in, but never in an entry the reader locked`() {
        val known = empty.copy(
            characters = listOf(
                Character("炭治郎", "Тандзиро"),
                Character("禰豆子", "Нэдзуко", locked = true),
            ),
        )
        val updated = BibleUpdates.apply(
            known,
            page(
                Character("炭治郎", "Танджиро", gender = Gender.Male, speech = "вежливый, мягкий"),
                Character("禰豆子", "Незуко", gender = Gender.Female, speech = "молчит"),
            ),
            10,
            "Глава 1",
            0,
        )

        updated.characters[0] shouldBe Character(
            "炭治郎",
            "Тандзиро",
            gender = Gender.Male,
            speech = "вежливый, мягкий",
            lastSeenChapter = 0,
        )
        updated.characters[1] shouldBe known.characters[1]
    }

    @Test
    fun `names are matched across width, case and spacing, and by aliases`() {
        val known = empty.copy(
            characters = listOf(Character("Ｋａｉ", "Кай", aliases = listOf(Alias("カイ君", "Кай-кун")))),
        )
        val updated = BibleUpdates.apply(
            known,
            page(Character("kai", "Каи"), Character("カイ 君", "Кайкун")),
            10,
            "Глава 1",
            0,
        )

        updated.characters shouldHaveSize 1
        updated.characters.single().name shouldBe "Кай"
    }

    @Test
    fun `another romanization of a known name keeps the first translation and becomes an alias`() {
        val first = BibleUpdates.apply(empty, page(Character("Yoo Joonghyeok", "Ю Чжунхёк")), 10, "259", 0)
        val later = BibleUpdates.apply(first, page(Character("YOO JOONGHYUK", "Ю Чунхёк")), 10, "259", 9)

        val character = later.characters.single()
        character.name shouldBe "Ю Чжунхёк"
        character.aliases shouldBe listOf(Alias("YOO JOONGHYUK", "Ю Чжунхёк"))
    }

    @Test
    fun `look-alike names stay different people`() {
        val first = BibleUpdates.apply(empty, page(Character("Biyu", "Бию"), Character("Minho", "Минхо")), 10, "1", 0)
        val later = BibleUpdates.apply(
            first,
            page(Character("Bihyung", "Бихён"), Character("Minha", "Минха")),
            10,
            "1",
            1,
        )

        later.characters.map { it.name } shouldBe listOf("Бию", "Минхо", "Бихён", "Минха")
    }

    @Test
    fun `a romanized name is found on the page in another romanization, as whole words`() {
        BibleUpdates.mentionsOriginal("THERE'S NO WAY YOO JOONGHYUK WOULD KNOW.", "Yoo Joonghyeok") shouldBe true
        BibleUpdates.mentionsOriginal("HEEWON-SSI!! OVER HERE!", "Hiwon") shouldBe true
        BibleUpdates.mentionsOriginal("HYEWON-SSI!! OVER HERE!", "Heewon") shouldBe false
        BibleUpdates.mentionsOriginal("JUNG HEEWON AND LEE JIHYE", "Jung Heewon") shouldBe true
        BibleUpdates.mentionsOriginal("CHIEF BIHYUNG", "Biyu") shouldBe false
        BibleUpdates.mentionsOriginal("Jinwoo's sword", "Jinwoo") shouldBe true
    }

    @Test
    fun `terms are added once, and a character's name is not a term`() {
        val updated = BibleUpdates.apply(
            empty,
            page(
                Character("炭治郎", "Тандзиро"),
                terms = listOf(
                    Term("鬼殺隊", "Корпус истребителей демонов", TermKind.Organization),
                    Term("鬼殺隊", "Отряд убийц демонов", TermKind.Organization),
                    Term("炭治郎", "Тандзиро"),
                    Term("", "пусто"),
                ),
            ),
            10,
            "Глава 1",
            0,
        )

        updated.terms.map { it.translation } shouldBe listOf("Корпус истребителей демонов")
    }

    @Test
    fun `page summaries make the chapter's story, in page order`() {
        var bible = empty
        bible = BibleUpdates.apply(bible, page(summary = "Тандзиро возвращается домой."), 10, "Глава 1", 2)
        bible = BibleUpdates.apply(bible, page(summary = "Тандзиро продаёт уголь."), 10, "Глава 1", 0)
        bible = BibleUpdates.apply(bible, page(summary = "Начало второй главы."), 11, "Глава 2", 0)

        bible.chapters.map { it.chapterName } shouldBe listOf("Глава 1", "Глава 2")
        bible.chapters[0].summary() shouldBe "Тандзиро продаёт уголь. Тандзиро возвращается домой."
    }

    @Test
    fun `names and terms on the page and sure speakers are marked as seen in this chapter`() {
        val known = empty.copy(
            characters = listOf(Character("炭治郎", "Тандзиро"), Character("善逸", "Дзэнъицу")),
            terms = listOf(Term("鬼殺隊", "Корпус истребителей демонов"), Term("呼吸", "Дыхание")),
        )
        val updated = BibleUpdates.apply(
            known,
            PageFindings(lines = listOf("鬼殺隊に入るんだ！"), speakers = listOf("Тандзиро")),
            42,
            "Глава 5",
            0,
        )

        updated.characters.map { it.lastSeenChapter } shouldBe listOf(42L, 0L)
        updated.terms.map { it.lastSeenChapter } shouldBe listOf(42L, 0L)
    }

    @Test
    fun `nothing new leaves the notes as they were`() {
        val known = empty.copy(characters = listOf(Character("炭治郎", "Тандзиро", lastSeenChapter = 10)))
        val same = BibleUpdates.apply(known, page(Character("炭治郎", "Тандзиро")), 10, "Глава 1", 0)

        same shouldBe known
        same.revision shouldBe 0
    }
}
