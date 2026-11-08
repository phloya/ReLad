package plus.translate

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RefusalsTest {

    @Test
    fun `a refusal in the page notes is told from a note about a page`() {
        Refusals.looksLike(
            "Отказываюсь переводить эту страницу: текст сексуализирует персонажа. Могу помочь с другим.",
        ) shouldBe true
        Refusals.looksLike("I can't help translate this page.") shouldBe true
        Refusals.looksLike("I won't translate this content.") shouldBe true
        Refusals.looksLike("Не могу помочь с переводом этой страницы.") shouldBe true
        // Notes about pages
        Refusals.looksLike("Гатс стоит на палубе и смотрит на меч.") shouldBe false
        Refusals.looksLike("Guts looks at the sword; no text on this page.") shouldBe false
        Refusals.looksLike("Фарнезе отказывается уходить с корабля.") shouldBe false
        Refusals.looksLike("Guts refuses to leave the ship.") shouldBe false
        Refusals.looksLike("") shouldBe false
    }
}
