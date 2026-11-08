package plus.core.pipeline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Runs page jobs newest-first: the page the reader just opened is the one they are looking at,
 * pages requested earlier (preloads, pages flipped past) wait. Only [maxPending] jobs wait at a time;
 * the oldest are dropped and reported to [onDropped] so they can be requested again later.
 */
internal class LifoWorkQueue(
    scope: CoroutineScope,
    parallelism: Int,
    private val maxPending: Int,
    private val onDropped: (String) -> Unit = {},
) {

    private class Task(val key: String, val block: suspend () -> Unit)

    private val lock = Any()
    private val pending = ArrayDeque<Task>()
    private val wake = Channel<Unit>(Channel.UNLIMITED)

    init {
        repeat(parallelism) {
            scope.launch {
                for (signal in wake) {
                    val task = synchronized(lock) { pending.removeFirstOrNull() } ?: continue
                    task.block()
                }
            }
        }
    }

    /** Adds a job, or moves an already waiting job with the same [key] to the front. */
    fun submit(key: String, block: suspend () -> Unit) {
        val dropped = mutableListOf<String>()
        synchronized(lock) {
            val existing = pending.indexOfFirst { it.key == key }
            if (existing >= 0) {
                pending.addFirst(pending.removeAt(existing))
                return
            }
            pending.addFirst(Task(key, block))
            while (pending.size > maxPending) {
                dropped += pending.removeLast().key
            }
        }
        dropped.forEach(onDropped)
        wake.trySend(Unit)
    }

    fun pendingKeys(): List<String> = synchronized(lock) { pending.map { it.key } }
}
