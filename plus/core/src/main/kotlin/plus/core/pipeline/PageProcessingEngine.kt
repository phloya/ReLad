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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    parallelism: Int = 4,
    maxPending: Int = 8,
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

    /** Each step's own limit on pages at once (see [PageProcessor.concurrency]). */
    private val permits = mutableMapOf<String, Semaphore>()

    private fun permitsFor(processor: PageProcessor) = synchronized(lock) {
        permits.getOrPut(processor.id) { Semaphore(processor.concurrency.coerceAtLeast(1)) }
    }
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
        val plan = plan(originalHash, original, context)
            ?: return Resolved(original, originalHash, pending = false)
        if (plan.remaining.isEmpty()) return Resolved(plan.image, originalHash, pending = false)

        val scheduled = synchronized(lock) {
            when {
                recentlyFailed(plan.finalKey) -> false
                plan.finalKey in inFlight -> true
                else -> {
                    inFlight += plan.finalKey
                    true
                }
            }
        }
        if (scheduled) {
            queue.submit(plan.finalKey) { run(originalHash, plan.finalKey, plan.image, plan.remaining, context) }
        }
        return Resolved(plan.image, originalHash, pending = scheduled)
    }

    /**
     * Processes [original] through every missing step in the caller's coroutine, for pages made
     * ready before the reader gets to them (a whole chapter at once). Returns when the page is
     * done or failed; at once if it is cached, failed a moment ago or already being processed.
     * Each step's [PageProcessor.concurrency] holds here too.
     */
    suspend fun prepare(original: ByteArray, context: PageContext) {
        val originalHash = sha256(original)
        val plan = plan(originalHash, original, context) ?: return
        if (plan.remaining.isEmpty()) return
        val claimed = synchronized(lock) {
            if (recentlyFailed(plan.finalKey) || plan.finalKey in inFlight) {
                false
            } else {
                inFlight += plan.finalKey
                true
            }
        }
        if (claimed) run(originalHash, plan.finalKey, plan.image, plan.remaining, context)
    }

    /** What a page still needs: the best cached version and the steps after it. `null`: no steps at all. */
    private class Plan(val image: ByteArray, val finalKey: String, val remaining: List<Step>)

    private fun plan(originalHash: String, original: ByteArray, context: PageContext): Plan? {
        val steps = activeSteps(originalHash, context)
        if (steps.isEmpty()) return null
        // The last step that is already cached
        for (i in steps.indices.reversed()) {
            val cached = cache.get(steps[i].key) ?: continue
            return Plan(cached, steps.last().key, steps.drop(i + 1))
        }
        return Plan(original, steps.last().key, steps)
    }

    private fun recentlyFailed(finalKey: String): Boolean {
        val failed = failedAt[finalKey] ?: return false
        return clock() - failed < RETRY_AFTER_MS
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
                    permitsFor(step.processor).withPermit { step.processor.process(image, context) }
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
