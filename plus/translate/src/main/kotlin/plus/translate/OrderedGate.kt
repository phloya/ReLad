package plus.translate

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.PriorityQueue
import kotlin.coroutines.resume

/**
 * At most [permits] blocks at once, and a waiting block with a smaller ticket goes first. Pages
 * are drawn in the order they were asked for (the page open in the reader and the ones after it),
 * not in the order their translations happened to come back: with a plain semaphore page 2 waited
 * 68 s while pages 8, 3, 10 and 9 were drawn.
 */
class OrderedGate(private val permits: Int) {

    private class Waiter(val ticket: Long, val order: Long, val continuation: CancellableContinuation<Unit>)

    private val lock = Any()
    private var free = permits
    private var arrivals = 0L
    private val waiting = PriorityQueue<Waiter>(compareBy<Waiter> { it.ticket }.thenBy { it.order })

    suspend fun <T> withPermit(ticket: Long, block: suspend () -> T): T {
        acquire(ticket)
        try {
            return block()
        } finally {
            release()
        }
    }

    private suspend fun acquire(ticket: Long) {
        synchronized(lock) {
            if (free > 0) {
                free--
                return
            }
        }
        suspendCancellableCoroutine { continuation ->
            val waiter = synchronized(lock) {
                // A permit may have come back in between
                if (free > 0) {
                    free--
                    null
                } else {
                    Waiter(ticket, arrivals++, continuation).also { waiting += it }
                }
            }
            if (waiter == null) {
                continuation.resume(Unit) { _, _, _ -> release() }
                return@suspendCancellableCoroutine
            }
            // Cancelled while waiting: it leaves the queue. Cancelled as the permit is handed over:
            // release() passes the permit on (see there)
            continuation.invokeOnCancellation { synchronized(lock) { waiting.remove(waiter) } }
        }
    }

    private fun release() {
        val next = synchronized(lock) {
            waiting.poll() ?: run {
                free++
                return
            }
        }
        // Handed over directly, the permit count stays; a waiter cancelled meanwhile passes it on
        next.continuation.resume(Unit) { _, _, _ -> release() }
    }

    /** Blocks running now. */
    fun busy(): Int = synchronized(lock) { permits - free }
}

/**
 * Tickets for [OrderedGate] in reading order: a chapter's pages from the page the reader opened
 * ([readingFrom][ticket], the first page when it is done in the background) to the end, then the
 * ones before it; the chapter asked for first goes first, the next one ahead after it. Pages come
 * in the order their images finish loading, which is not the order they are read in: the first
 * page to come in can't stand for the page opened.
 */
class ReadingOrder {

    private class Start(val order: Long, val pageIndex: Int)

    private val starts = java.util.concurrent.ConcurrentHashMap<Long, Start>()
    private val chapters = java.util.concurrent.atomic.AtomicLong()

    fun ticket(chapterId: Long, pageIndex: Int, readingFrom: Int? = null): Long {
        val start = starts.compute(chapterId) { _, known ->
            when {
                known == null -> Start(chapters.incrementAndGet(), readingFrom ?: 0)
                // Done in the background first, then opened in the reader: from the page opened
                readingFrom != null && readingFrom != known.pageIndex -> Start(known.order, readingFrom)
                else -> known
            }
        }!!
        val inChapter = if (pageIndex >= start.pageIndex) {
            (pageIndex - start.pageIndex).toLong()
        } else {
            BEFORE_START + (start.pageIndex - pageIndex)
        }
        return start.order * CHAPTER + inChapter
    }

    private companion object {
        /** Pages before the first one asked for come after every page from it on. */
        const val BEFORE_START = 100_000L
        const val CHAPTER = 1_000_000L
    }
}
