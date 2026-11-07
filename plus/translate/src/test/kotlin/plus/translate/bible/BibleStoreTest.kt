package plus.translate.bible

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class BibleStoreTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `notes survive a new store, per title and language`() = runTest {
        BibleStore(dir).update(1, "A", "ru", "ja") { it.copy(terms = listOf(Term("鬼", "демон"))) }

        val again = BibleStore(dir)
        again.get(1, "A", "ru", "ja").terms shouldBe listOf(Term("鬼", "демон"))
        again.get(1, "A", "en", "ja").terms shouldBe emptyList()
        again.get(2, "B", "ru", "ja").mangaTitle shouldBe "B"
    }

    @Test
    fun `pages finishing at the same time don't lose each other's changes`() = runTest {
        val store = BibleStore(dir)
        (1..20).map { n ->
            async { store.update(1, "A", "ru", "ja") { it.copy(terms = it.terms + Term("t$n", "т$n")) } }
        }.awaitAll()

        BibleStore(dir).get(1, "A", "ru", "ja").terms.size shouldBe 20
    }

    @Test
    fun `a broken file is set aside and translation starts over`() = runTest {
        File(dir, "1-ru.json").writeText("{ not json")

        BibleStore(dir).get(1, "A", "ru", "ja").terms shouldBe emptyList()
        dir.list()!!.single() shouldStartWith "1-ru.broken-"
    }

    @Test
    fun `export and import give the same notes`() {
        val store = BibleStore(dir)
        val bible = TitleBible(1, "A", "ru", characters = listOf(Character("炭治郎", "Тандзиро", gender = Gender.Male)))

        store.import(store.export(bible)) shouldBe bible
    }
}
