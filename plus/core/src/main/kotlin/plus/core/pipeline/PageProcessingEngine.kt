package plus.core.pipeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.security.MessageDigest

/**
 * Re:Lad's page pipeline. The reader hands over every page image it shows; the engine answers
 * at once with the best version it already has (the original, or a cached processed version)
 * and processes missing steps in the background. When a page is done, its original's hash is
 * sent to [completed] and the reader asks again.
 *
 * Every step's output is cached separately (key = hash of the previous key + step id + signature),
 * so switching translation off still shows the enhanced page instantly.
 */
class PageProcessingEngine(
    private val processors: () -> List<PageProcessor>,
    private val cache: PageCache,
    scope: CoroutineScope,
    parallelism: Int = 2,
    maxPending: Int = 6,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Result of [resolve]: the bytes to show now and whether more work is on its way. */
    class Resolved(val image: ByteArray, val originalHash: String, val pending: Boolean)

    class Failure(val originalHash: String, val processorId: String, val error: Throwable)

    private val _completed = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /** Hashes of original pages whose processed version just became available. */
    val completed: SharedFlow<String> = _completed.asSharedFlow()

    private val _failures = MutableSharedFlow<Failure>(extraBufferCapacity = 16)

    /** Steps that failed (no API key, network error...). The page stays at the last good step. */
    val failures: SharedFlow<Failure> = _failures.asSharedFlow()

    private val _settingsVersion = MutableStateFlow(0)

    /** Bumped by [onSettingsChanged]; the reader reloads visible pages when it changes. */
    val settingsVersion: StateFlow<Int> = _settingsVersion.asStateFlow()

    private val lock = Any()
    private val inFlight = mutableSetOf<String>()
    private val failedAt = mutableMapOf<String, Long>()

    private val queue = LifoWorkQueue(
        scope = scope,
        parallelism = parallelism,
        maxPending = maxPending,
        onDropped = { key -> synchronized(lock) { inFlight.remove(key) } },
    )

    /** Call when any processor's settings change, so pages get reprocessed with the new ones. */
    fun onSettingsChanged() {
        synchronized(lock) { failedAt.clear() }
        _settingsVersion.update { it + 1 }
    }

    /**
     * Returns the most processed version of [original] that is already cached and schedules
     * the missing steps. Cheap apart from hashing; call it from a background thread.
     */
    fun resolve(original: ByteArray, context: PageContext): Resolved {
        val originalHash = sha256(original)
        val steps = activeSteps(originalHash, context)
        if (steps.isEmpty()) return Resolved(original, originalHash, pending = false)

        // Find the last step that is already cached
        var done = -1
        var image = original
        for (i in steps.indices.reversed()) {
            val cached = cache.get(steps[i].key) ?: continue
            done = i
            image = cached
            break
        }
        if (done == steps.lastIndex) return Resolved(image, originalHash, pending = false)

        val finalKey = steps.last().key
        val scheduled = synchronized(lock) {
            val failed = failedAt[finalKey]
            when {
                failed != null && clock() - failed < RETRY_AFTER_MS -> false
                finalKey in inFlight -> true
                else -> {
                    inFlight += finalKey
                    true
                }
            }
        }
        if (scheduled) {
            val remaining = steps.drop(done + 1)
            val input = image
            queue.submit(finalKey) { run(originalHash, finalKey, input, remaining, context) }
        }
        return Resolved(image, originalHash, pending = scheduled)
    }

    /** Metadata the last step stored for this page (e.g. translated text regions), if any. */
    fun metadata(original: ByteArray, context: PageContext): String? {
        val steps = activeSteps(sha256(original), context)
        return steps.lastOrNull()?.let { cache.metadata(it.key) }
    }

    private suspend fun run(
        originalHash: String,
        finalKey: String,
        input: ByteArray,
        steps: List<Step>,
        context: PageContext,
    ) {
        var image = input
        try {
            for (step in steps) {
                val result = try {
                    step.processor.process(image, context)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    synchronized(lock) { failedAt[finalKey] = clock() }
                    _failures.tryEmit(Failure(originalHash, step.processor.id, e))
                    return
                }
                cache.put(step.key, result)
                image = result.image
            }
            _completed.tryEmit(originalHash)
        } finally {
            synchronized(lock) { inFlight.remove(finalKey) }
        }
    }

    private class Step(val processor: PageProcessor, val key: String)

    private fun activeSteps(originalHash: String, context: PageContext): List<Step> {
        var key = originalHash
        return processors()
            .sortedBy { it.order }
            .mapNotNull { processor ->
                val signature = processor.signature(context) ?: return@mapNotNull null
                key = sha256("$key|${processor.id}|$signature".toByteArray())
                Step(processor, key)
            }
    }

    companion object {
        /** A failed page is not retried automatically for this long (settings changes reset it). */
        const val RETRY_AFTER_MS = 60_000L

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
