package plus.translate

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ChapterVersionsTest {

    @TempDir
    lateinit var dir: File

    private val file get() = File(dir, "chapters.json")

    @Test
    fun `a chapter shows its original until the reader asks for the translation`() {
        val versions = ChapterVersions(file)
        versions.get(7).shouldBeNull()
        versions.translates(7) shouldBe false
        versions.showsTranslation(7) shouldBe false

        versions.startTranslating(7)
        versions.translates(7) shouldBe true
        versions.showsTranslation(7) shouldBe true

        // The original chosen in the reader: the translation goes on, the original is shown
        versions.showOriginal(7, true)
        versions.translates(7) shouldBe true
        versions.showsTranslation(7) shouldBe false
        // Translate again from the chapter list: shown translated again
        versions.startTranslating(7)
        versions.showsTranslation(7) shouldBe true
    }

    @Test
    fun `leaving the reader or the app keeps how far a chapter got`() {
        val versions = ChapterVersions(file)
        versions.startTranslating(7)
        versions.pageCount(7, 20)
        for (page in 0 until 10) versions.pageTranslated(7, page, "v4|claude|ru")
        versions.progress(7, "v4|claude|ru").done shouldBe 10

        // A new store on the same file (the app started again): still 10 of 20, not the pages seen since
        val again = ChapterVersions(file)
        val progress = again.progress(7, "v4|claude|ru")
        progress.done shouldBe 10
        progress.total shouldBe 20
        progress.complete shouldBe false
        again.showsTranslation(7) shouldBe true
    }

    @Test
    fun `pages translated with other settings don't count`() {
        val versions = ChapterVersions(file)
        versions.startTranslating(7)
        versions.pageCount(7, 3)
        versions.pageTranslated(7, 0, "v4|claude|ru")
        versions.pageTranslated(7, 1, "v4|claude|en")
        versions.pageTranslated(7, 2, "v4|claude|ru")

        versions.progress(7, "v4|claude|ru").done shouldBe 2
        versions.progress(7, "v4|claude|en").done shouldBe 1
        versions.progress(7, null).done shouldBe 0
        versions.pageTranslated(7, 1, "v4|claude|ru")
        versions.progress(7, "v4|claude|ru").complete shouldBe true
    }

    @Test
    fun `translating again forgets the pages until they are in again`() {
        val versions = ChapterVersions(file)
        versions.startTranslating(7)
        versions.pageCount(7, 2)
        versions.pageTranslated(7, 0, "s")
        versions.pageTranslated(7, 1, "s")
        versions.pageForgotten(7, 1)
        versions.progress(7, "s").done shouldBe 1
        versions.pagesForgotten(7)
        versions.progress(7, "s").done shouldBe 0
        // The reader's page count is used before the chapter was loaded to be translated
        versions.progress(8, "s", pages = 12).total shouldBe 12
    }

    @Test
    fun `every change is announced, a repeat is not`() {
        val versions = ChapterVersions(file)
        val start = versions.changes.value
        versions.startTranslating(7)
        versions.pageTranslated(7, 0, "s")
        versions.changes.value shouldBe start + 2
        versions.pageTranslated(7, 0, "s")
        versions.startTranslating(7)
        versions.changes.value shouldBe start + 2
    }
}
