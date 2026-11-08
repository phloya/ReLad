package plus.core.pipeline

import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class LifoWorkQueueTest {

    @Test
    fun `newest job runs first`() = runTest {
        val ran = mutableListOf<String>()
        val queue = queue(maxPending = 10)

        listOf("a", "b", "c").forEach { key -> queue.submit(key) { ran += key } }
        advanceUntilIdle()

        ran shouldContainExactly listOf("c", "b", "a")
    }

    @Test
    fun `resubmitting a waiting job moves it to the front`() = runTest {
        val ran = mutableListOf<String>()
        val queue = queue(maxPending = 10)

        listOf("a", "b", "c", "a").forEach { key -> queue.submit(key) { ran += key } }
        advanceUntilIdle()

        ran shouldContainExactly listOf("a", "c", "b")
    }

    @Test
    fun `oldest jobs are dropped when too many wait`() = runTest {
        val ran = mutableListOf<String>()
        val dropped = mutableListOf<String>()
        val queue = queue(maxPending = 2, onDropped = { dropped += it })

        listOf("a", "b", "c", "d").forEach { key -> queue.submit(key) { ran += key } }
        advanceUntilIdle()

        ran shouldContainExactly listOf("d", "c")
        dropped shouldContainExactly listOf("a", "b")
    }

    private fun TestScope.queue(maxPending: Int, onDropped: (String) -> Unit = {}) = LifoWorkQueue(
        scope = workScope(),
        parallelism = 1,
        maxPending = maxPending,
        onDropped = onDropped,
    )

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
