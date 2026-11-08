package plus.translate.bible

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import plus.translate.Box
import plus.translate.RegionType
import plus.translate.TextRegion

class TranslationChecksTest {

    private val bible = TitleBible(
        mangaId = 1,
        mangaTitle = "Test",
        language = "ru",
        characters = listOf(Character("炭治郎", "Тандзиро", aliases = listOf(Alias("炭治郎くん", "Тандзиро-кун")))),
        terms = listOf(Term("鬼殺隊", "Корпус истребителей демонов")),
    )

    private fun line(text: String, translation: String) =
        TextRegion(RegionType.Bubble, Box(0, 0, 10, 10), text, translation)

    @Test
    fun `grammar endings on names and terms are fine`() {
        val regions = listOf(
            line("鬼殺隊に入れ！", "Вступай в Корпус истребителей демонов!"),
            line("鬼殺隊の剣士だ", "Он мечник Корпуса истребителей демонов."),
            line("炭治郎！", "Тандзиро!"),
            line("炭治郎と行く", "Я пойду с Тандзиро."),
        )
        TranslationChecks.check(regions, bible).shouldBeEmpty()
    }

    @Test
    fun `another word for a name or term is flagged with the wanted one`() {
        val regions = listOf(
            line("鬼殺隊に入れ！", "Вступай в Отряд охотников!"),
            line("炭治郎くん、待って", "Танджиро, подожди!"),
        )
        TranslationChecks.check(regions, bible) shouldBe listOf(
            TranslationChecks.Problem(0, TranslationChecks.Kind.WrongName, "Корпус истребителей демонов"),
            TranslationChecks.Problem(1, TranslationChecks.Kind.WrongName, "Тандзиро-кун"),
        )
    }

    @Test
    fun `original script left in the translation is flagged, except for CJK targets`() {
        val regions = listOf(line("ありがとう", "Спасибо, ありがとう"), line("고마워", "Спасибо"))

        TranslationChecks.check(regions, bible) shouldBe
            listOf(TranslationChecks.Problem(0, TranslationChecks.Kind.Untranslated))
        TranslationChecks.check(regions, bible.copy(language = "zh-Hans")).shouldBeEmpty()
    }

    @Test
    fun `a name spelled another way in an English original is still checked`() {
        val english = bible.copy(characters = listOf(Character("Yoo Joonghyeok", "Ю Чжунхёк")))
        val regions = listOf(
            line("YOO JOONGHYEOK WASN'T SUPPOSED TO", "Ю Чжунхёку не должны были"),
            line("THERE'S NO WAY YOO JOONGHYUK WOULD KNOW.", "Ну, Ю Чунхёк этого точно не знает."),
        )
        TranslationChecks.check(regions, english) shouldBe
            listOf(TranslationChecks.Problem(1, TranslationChecks.Kind.WrongName, "Ю Чжунхёк"))
    }

    @Test
    fun `a word half in Latin letters is flagged`() {
        val regions = listOf(
            line("THEY'RE BUILDING UP FORCES IN GYEONGGI.", "Они стягивают силы в Кёнгgи."),
            line("S-RANK HUNTER", "Охотник S-ранга"),
            line("OK!", "OK!"),
        )
        TranslationChecks.check(regions, bible) shouldBe
            listOf(TranslationChecks.Problem(0, TranslationChecks.Kind.MixedScript))
    }

    @Test
    fun `a line copied in English into a Russian page is flagged, names and tags are not`() {
        val regions = listOf(
            line("THE POLICE ARE ALWAYS LOOKING OUT FOR YOU.", "THE POLICE ARE ALWAYS LOOKING OUT FOR YOU."),
            line("HP 100/100", "HP 100/100"),
            line("POLICE", "POLICE"),
            line("SUNG JINWOO", "Сон Джину"),
        )
        TranslationChecks.check(regions, bible) shouldBe
            listOf(TranslationChecks.Problem(0, TranslationChecks.Kind.Untranslated))
        TranslationChecks.check(regions.take(1), bible.copy(language = "en")).shouldBeEmpty()
    }

    @Test
    fun `stems allow Russian endings but not other words`() {
        TranslationChecks.mentions("с Корпусом истребителей демонов", "Корпус истребителей демонов") shouldBe true
        TranslationChecks.mentions("с Отрядом истребителей", "Корпус истребителей демонов") shouldBe false
        TranslationChecks.mentions("Кай!", "Кай") shouldBe true
    }

    @Test
    fun `Latin names from English scanlations are matched as whole words only`() {
        BibleUpdates.mentionsOriginal("Kai, run!", "Kai") shouldBe true
        BibleUpdates.mentionsOriginal("A kaiju is coming!", "Kai") shouldBe false
        BibleUpdates.mentionsOriginal("Sung   Jinwoo is here", "Sung Jinwoo") shouldBe true
        BibleUpdates.mentionsOriginal("鬼殺隊に入れ", "鬼殺隊") shouldBe true

        val english = bible.copy(characters = listOf(Character("Kai", "Кай")))
        TranslationChecks.check(listOf(line("A kaiju!", "Кайдзю!")), english).shouldBeEmpty()
    }
}
