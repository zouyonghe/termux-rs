package com.termux.rust

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Single-thread persistence worker. The session/FFI owner thread never does
 * disk I/O: it hands over immutable snapshots; this worker coalesces them so
 * only the NEWEST pending snapshot is ever written (a stale write can never
 * overwrite a newer state). Loads run here too — the owner only applies the
 * decoded records.
 *
 * Failure semantics: a failed save keeps the snapshot as pending retry state
 * and retries with bounded exponential backoff (100 ms doubling, capped at
 * 2 s) — never a hot loop. A newer submission always replaces the failed
 * older snapshot. Logs are deduplicated (first failure, then every 16th).
 */
internal class SessionPersistenceWorker(
    private val store: SessionStateStore,
    private val logError: (String, Throwable) -> Unit = { _, _ -> },
) {
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "termux-persistence")
    }
    private val pending = AtomicReference<List<PersistedSession>?>(null)
    private val draining = AtomicBoolean(false)
    private val retryScheduled = AtomicBoolean(false)
    private val failureStreak = AtomicInteger(0)

    /** Latest-wins submit. Never blocks the caller. */
    fun submitLatest(snapshot: List<PersistedSession>) {
        pending.set(snapshot)
        schedule()
    }

    /** Loads records off the owner thread; [apply] is invoked on THIS
     *  executor with the decoded (already validated) records — callers must
     *  hand them to the owner thread for application. */
    fun loadAsync(apply: (List<PersistedSession>) -> Unit) {
        executor.execute {
            val loaded = runCatching { store.load() }.getOrElse { emptyList() }
            apply(loaded)
        }
    }

    /** Bounded flush + shutdown for service destroy: drains any pending or
     *  retry-scheduled snapshot immediately, then stops the worker. Returns
     *  false on timeout. */
    fun shutdown(timeoutMs: Long): Boolean {
        val done = CountDownLatch(1)
        // Runs after already-queued drains; pending retries are forced now
        // instead of waiting out their backoff.
        executor.execute {
            drain()
            done.countDown()
        }
        val drained = done.await(timeoutMs, TimeUnit.MILLISECONDS)
        executor.shutdown()
        return drained && executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
    }

    fun shutdownNow() {
        executor.shutdownNow()
    }

    private fun schedule() {
        if (!draining.compareAndSet(false, true)) return
        try {
            executor.execute { drain() }
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            // Worker is shutting down: drop quietly instead of pretending the
            // snapshot is in flight or crashing the caller.
            draining.set(false)
        }
    }

    private fun drain() {
        try {
            while (true) {
                val snapshot = pending.getAndSet(null) ?: return
                try {
                    store.save(snapshot)
                    failureStreak.set(0)
                } catch (error: Throwable) {
                    val streak = failureStreak.incrementAndGet()
                    if (streak == 1 || streak % 16 == 0) {
                        logError("session state persist failed (x$streak)", error)
                    }
                    // Keep the failed snapshot for retry unless a newer
                    // submission already replaced it, then back off.
                    pending.compareAndSet(null, snapshot)
                    scheduleRetry(streak)
                    return
                }
            }
        } finally {
            draining.set(false)
            // A submit that raced the reset must not be lost.
            if (pending.get() != null) schedule()
        }
    }

    private fun scheduleRetry(streak: Int) {
        val delayMs = (100L * (1L shl streak.coerceAtMost(4))).coerceAtMost(2_000L)
        if (retryScheduled.compareAndSet(false, true)) {
            try {
                executor.schedule(
                    {
                        retryScheduled.set(false)
                        schedule()
                    },
                    delayMs,
                    TimeUnit.MILLISECONDS,
                )
            } catch (error: java.util.concurrent.RejectedExecutionException) {
                retryScheduled.set(false)
            }
        }
    }
}
