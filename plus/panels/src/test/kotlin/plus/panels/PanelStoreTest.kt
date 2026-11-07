package plus.panels

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PanelStoreTest {

    @TempDir
    lateinit var dir: File

    private val layout = PageLayout(
        panels = listOf(Box(0.1f, 0.1f, 0.9f, 0.5f)),
        crop = Box.FULL,
        gaps = listOf(Span(0.5f, 0.55f)),
        aspect = 0.7f,
    )

    @Test
    fun `detects once, then answers from memory and from disk`() {
        var runs = 0
        val image = "page".toByteArray()
        val detect = { _: ByteArray -> runs++.let { layout } }

        PanelStore(dir).layout(image, detect) shouldBe layout
        PanelStore(dir).layout(image, detect) shouldBe layout // a new store: read from disk
        runs shouldBe 1
    }

    @Test
    fun `keeps no more than the limit of files`() {
        val store = PanelStore(dir, maxFiles = 2)
        listOf("a", "b", "c").forEachIndexed { i, key ->
            store.put(key, layout)
            File(dir, "$key.json").setLastModified(1_000L * (i + 1))
        }
        store.put("d", layout)
        dir.list()!!.sorted() shouldBe listOf("c.json", "d.json")
    }

    @Test
    fun `layouts survive a round trip through JSON`() {
        PageLayout.fromJson(layout.toJson()) shouldBe layout
        PageLayout.fromJson("not json") shouldBe null
    }
}
