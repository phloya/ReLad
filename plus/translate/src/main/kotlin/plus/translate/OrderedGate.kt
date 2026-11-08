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
