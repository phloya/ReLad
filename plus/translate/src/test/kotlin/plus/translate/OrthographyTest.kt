package plus.translate

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class OrthographyTest {

    @Test
    fun `Uzbek letters get the right marks whatever quote the model used`() {
        Orthography.fix("uz", "Mos ko‘rinadi, to'g'ri, chalg`itaman") shouldBe "Mos koʻrinadi, toʻgʻri, chalgʻitaman"
        Orthography.fix("uz", "ta'qibchilarni, san’at") shouldBe "taʼqibchilarni, sanʼat"
        Orthography.fix("uz", "Sen 'Torfinn' deding") shouldBe "Sen 'Torfinn' deding"
        Orthography.fix("uz", "koʻrinadi, taʼqib") shouldBe "koʻrinadi, taʼqib"
    }

    @Test
    fun `other languages are left alone`() {
        Orthography.fix("en", "Don't go") shouldBe "Don't go"
    }
}
