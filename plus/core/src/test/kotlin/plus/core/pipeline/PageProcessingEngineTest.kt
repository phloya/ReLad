package plus.core.pipeline

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PageProcessingEngineTest {

    @TempDir
    lateinit var dir: File

    private val context = PageContext(
        mangaId = 1,
        mangaTitle = "Manga",
        chapterId = 2,
        chapterName = "Ch. 1",
        pageIndex = 0,
        sourceLanguage = "ja",
    )

    /** Appends its tag to the input, so the output shows which steps ran and in which order. */
    private class TagProcessor(override val id: String, override val order: Int) : PageProcessor {
        var enabled = true
        var signature = "v1"
        var fail = false
        var calls = 0

        override fun signature(context: PageContext): String? = if (enabled) signature else null

        override suspend fun process(input: ByteArray, context: PageContext): ProcessedPage {
            calls++
            if (fail) error("$id failed")
            return ProcessedPage(input + "+$id".toByteArray(), metadata = "meta-$id")
        }
    }

    private fun TestScope.engine(vararg processors: PageProcessor, now: () -> Long = { 0L }) = PageProcessingEngine(
        processors = { processors.toList() },
        cache = PageCache(dir),
        scope = workScope(),
        parallelism = 1,
        clock = now,
    )

    private val original = "page".toByteArray()

    @Test
    fun `no active processors returns the original and schedules nothing`() = runTest {
        val tag = TagProcessor("a", 1).apply { enabled = false }
        val engine = engine(tag)

        val resolved = engine.resolve(original, context)
        advanceUntilIdle()

        resolved.image.decodeToString() shouldBe "page"
        resolved.pending shouldBe false
        tag.calls shouldBe 0
    }

    @Test
    fun `processes in order, reports completion, then serves the cached result`() = runTest {
        val first = TagProcessor("first", 1)
        val second = TagProcessor("second", 2)
        val engine = engine(second, first)
        val completed = mutableListOf<String>()
        engine.completed.onEach { completed += it }.launchIn(workScope())
        runCurrent() // subscribe before anything is emitted

        val before = engine.resolve(original, context)
        before.image.decodeToString() shouldBe "page"
        before.pending shouldBe true

        advanceUntilIdle()
        completed shouldContainExactly listOf(before.originalHash)

        val after = engine.resolve(original, context)
        after.image.decodeToString() shouldBe "page+first+second"
        after.pending shouldBe false
        engine.metadata(original, context) shouldBe "meta-second"
        first.calls shouldBe 1
        second.calls shouldBe 1
    }

    @Test
    fun `switching off the last step shows the cached earlier step at once`() = runTest {
        val first = TagProcessor("first", 1)
        val second = TagProcessor("second", 2)
        val engine = engine(first, second)
        engine.resolve(original, context)
        advanceUntilIdle()

        second.enabled = false
        val resolved = engine.resolve(original, context)

        resolved.image.decodeToString() shouldBe "page+first"
        resolved.pending shouldBe false
        first.calls shouldBe 1
    }

    @Test
    fun `a new signature reruns only the changed step`() = runTest {
        val first = TagProcessor("first", 1)
        val second = TagProcessor("second", 2)
        val engine = engine(first, second)
        engine.resolve(original, context)
        advanceUntilIdle()

        second.signature = "v2"
        val resolved = engine.resolve(original, context)
        resolved.image.decodeToString() shouldBe "page+first"
        resolved.pending shouldBe true
        advanceUntilIdle()

        first.calls shouldBe 1
        second.calls shouldBe 2
    }

    /** Waits until let go, counting how many pages it works on at once. */
    private class SlowProcessor(override val id: String, override val concurrency: Int) : PageProcessor {
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var running = 0
        var most = 0

        override val order = 1

        override fun signature(context: PageContext) = "v1"

        override suspend fun process(input: ByteArray, context: PageContext): ProcessedPage {
            running++
            most = maxOf(most, running)
            release.await()
            running--
            return ProcessedPage(input)
        }
    }

    @Test
    fun `a step works on no more pages at once than it allows`() = runTest {
        val slow = SlowProcessor("slow", concurrency = 2)
        val engine = PageProcessingEngine(
            processors = { listOf(slow) },
            cache = PageCache(dir),
            scope = workScope(),
            parallelism = 4,
        )
        repeat(4) { engine.resolve("page $it".toByteArray(), context) }
        runCurrent()
        slow.most shouldBe 2
        slow.release.complete(Unit)
        advanceUntilIdle()
        slow.running shouldBe 0
    }

    @Test
    fun `asking again while a page is in work does not start it twice`() = runTest {
        val tag = TagProcessor("a", 1)
        val engine = engine(tag)

        engine.resolve(original, context).pending shouldBe true
        engine.resolve(original, context).pending shouldBe true
        advanceUntilIdle()

        tag.calls shouldBe 1
    }

    @Test
    fun `a failed page is reported and not retried until the settings change`() = runTest {
        var now = 0L
        val tag = TagProcessor("a", 1).apply { fail = true }
        val engine = engine(tag, now = { now })
        val failures = mutableListOf<PageProcessingEngine.Failure>()
        engine.failures.onEach { failures += it }.launchIn(workScope())
        runCurrent() // subscribe before anything is emitted

        engine.resolve(original, context)
        advanceUntilIdle()
        failures.map { it.processorId } shouldContainExactly listOf("a")

        now = 1_000
        val again = engine.resolve(original, context)
        again.pending shouldBe false
        again.image.decodeToString() shouldBe "page"

        tag.fail = false
        engine.onSettingsChanged()
        engine.resolve(original, context).pending shouldBe true
        advanceUntilIdle()
        engine.resolve(original, context).image.decodeToString() shouldBe "page+a"
    }

    @Test
    fun `a failed page is retried after the waiting time`() = runTest {
        var now = 0L
        val tag = TagProcessor("a", 1).apply { fail = true }
        val engine = engine(tag, now = { now })
        engine.resolve(original, context)
        advanceUntilIdle()

        now = PageProcessingEngine.RETRY_AFTER_MS + 1
        engine.resolve(original, context).pending shouldBe true
    }

    private val workScopes = mutableListOf<CoroutineScope>()

    /**
     * Workers run on the test scheduler, so jobs wait until advanceUntilIdle(). Not backgroundScope:
     * advanceUntilIdle() does not wait for background work.
     */
    private fun TestScope.workScope() =
        CoroutineScope(StandardTestDispatcher(testScheduler) + Job()).also { workScopes += it }

    @AfterEach
    fun cancelWorkers() = workScopes.forEach { it.cancel() }
}
