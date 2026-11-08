package plus.translate

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class OrderedGateTest {

    @Test
    fun `waiting blocks go by ticket, not by arrival`() = runTest {
        val gate = OrderedGate(2)
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<Long>()
        // Two pages take both permits; pages 8, 3, 10, 2 then wait (as on the phone)
        for (ticket in listOf(1L, 5L, 8L, 3L, 10L, 2L)) {
            launch {
                gate.withPermit(ticket) {
                    order += ticket
                    release.await()
                }
            }
            runCurrent()
        }
        gate.busy() shouldBe 2
        release.complete(Unit)
        advanceUntilIdle()

        order shouldBe listOf(1L, 5L, 2L, 3L, 8L, 10L)
        gate.busy() shouldBe 0
    }

    @Test
    fun `a waiter cancelled in the queue doesn't take or lose a permit`() = runTest {
        val gate = OrderedGate(1)
        val release = CompletableDeferred<Unit>()
        val ran = mutableListOf<Long>()
        val holder = launch { gate.withPermit(1) { release.await() } }
        runCurrent()
        val cancelled = launch { gate.withPermit(2) { ran += 2 } }
        val next = launch { gate.withPermit(3) { ran += 3 } }
        runCurrent()

        cancelled.cancel()
        release.complete(Unit)
        advanceUntilIdle()

        ran shouldBe listOf(3L)
        holder.isCompleted shouldBe true
        next.isCompleted shouldBe true
        gate.busy() shouldBe 0
    }

    @Test
    fun `a failing block gives its permit back`() = runTest {
        val gate = OrderedGate(1)
        runCatching { gate.withPermit(1) { error("drawing failed") } }
        gate.busy() shouldBe 0
        gate.withPermit(2) { gate.busy() } shouldBe 1
    }

    @Test
    fun `reading order starts at the page opened, then the pages before it, then the next chapter`() {
        val order = ReadingOrder()
        val opened = order.ticket(chapterId = 7, pageIndex = 4)
        val later = order.ticket(chapterId = 7, pageIndex = 9)
        val before = order.ticket(chapterId = 7, pageIndex = 1)
        val nextChapter = order.ticket(chapterId = 8, pageIndex = 0)
        val afterOpened = order.ticket(chapterId = 7, pageIndex = 5)

        listOf(opened, afterOpened, later, before, nextChapter) shouldBe
            listOf(opened, afterOpened, later, before, nextChapter).sorted()
    }
}
