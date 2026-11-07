package plus.enhance

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

class EnhanceModelsTest {

    @Test
    fun `every file is pinned to a commit and has a hash`() {
        EnhanceModels.all.flatMap { listOf(it.param, it.weights) }.forEach { file ->
            file.url shouldStartWith "https://raw.githubusercontent.com/"
            file.url shouldMatch Regex(".*/[0-9a-f]{40}/.*")
            file.sha256 shouldMatch Regex("[0-9a-f]{64}")
        }
    }

    @Test
    fun `models with a non-commercial license are hidden unless allowed`() {
        val nonCommercial = EnhanceModels.all.count { !it.license.commercialUse }

        EnhanceModels.available(allowNonCommercial = false).all { it.license.commercialUse } shouldBe true
        EnhanceModels.available(allowNonCommercial = true) shouldHaveSize EnhanceModels.all.size
        EnhanceModels.available(allowNonCommercial = false) shouldHaveSize EnhanceModels.all.size - nonCommercial
    }

    @Test
    fun `four denoise levels`() {
        EnhanceModels.cunetByNoise.map { it.id } shouldBe (0..3).map { "waifu2x-cunet-2x-noise$it" }
        EnhanceModels.cunetByNoise.all { it.scale == 2 } shouldBe true
    }
}
