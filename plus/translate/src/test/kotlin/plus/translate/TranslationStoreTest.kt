package plus.translate

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TranslationStoreTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `a translation is kept and found by the page and what changes its text`() {
        val store = TranslationStore(dir)
        val page = "page".toByteArray()
        val key = TranslationStore.key(page, "v4|claude|ru")
        store.get(key).shouldBeNull()
        store.put(key, """{"language":"ru","regions":[]}""")
        // A new store on the same folder (the app started again) finds it
        TranslationStore(dir).get(key) shouldBe """{"language":"ru","regions":[]}"""
        store.count() shouldBe 1

        // Another language or model is another translation; the same page and settings are the same key
        TranslationStore.key(page, "v4|claude|en") shouldNotBe key
        TranslationStore.key("other".toByteArray(), "v4|claude|ru") shouldNotBe key
        TranslationStore.key(page, "v4|claude|ru") shouldBe key
    }

    @Test
    fun `one page's translation can be forgotten`() {
        val store = TranslationStore(dir)
        store.put("a", "{}")
        store.put("b", "{}")
        store.remove("a")
        store.get("a").shouldBeNull()
        store.get("b") shouldBe "{}"
    }

    @Test
    fun `deleting forgets every translation`() {
        val store = TranslationStore(dir)
        store.put("a", "{}")
        store.put("b", "{}")
        store.clear()
        store.count() shouldBe 0
        store.get("a").shouldBeNull()
    }
}
