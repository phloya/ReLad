package plus.core.pipeline

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PageCacheTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `stores image and metadata`() {
        val cache = PageCache(dir)
        cache.put("k", ProcessedPage("img".toByteArray(), "meta"))

        cache.get("k")?.decodeToString() shouldBe "img"
        cache.metadata("k") shouldBe "meta"
        cache.contains("other") shouldBe false
    }

    @Test
    fun `drops least recently used entries when over the limit`() {
        val cache = PageCache(dir, maxBytes = 25)
        cache.put("old", ProcessedPage(ByteArray(10)))
        File(dir, "old.img").setLastModified(1_000)
        cache.put("used", ProcessedPage(ByteArray(10)))
        File(dir, "used.img").setLastModified(2_000)
        cache.get("used") // touches it

        cache.put("new", ProcessedPage(ByteArray(10)))

        cache.contains("old") shouldBe false
        cache.contains("used") shouldBe true
        cache.contains("new") shouldBe true
    }
}
