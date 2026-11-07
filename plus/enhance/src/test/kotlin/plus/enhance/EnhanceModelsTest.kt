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
    fun `a model for every strength, all x2 with shared SE features`() {
        EnhanceModels.cugan.keys shouldBe Strength.entries.toSet()
        EnhanceModels.cugan.forEach { (strength, model) ->
            model.strength shouldBe strength
            model.scale shouldBe 2
            model.sharedBlobs shouldBe listOf("gap0", "gap1", "gap2", "gap3")
        }
        EnhanceModels.all.map { it.id }.toSet() shouldHaveSize 4
    }
}
