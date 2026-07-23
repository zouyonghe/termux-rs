package com.termux.rust

/**
 * Lifecycle core of the non-exported TermuxService: owns the [SessionRegistry]
 * and defines the deterministic shutdown sequence. The Android shell
 * ([TermuxService]) owns the owner [android.os.HandlerThread] and the drive
 * loop; this class is pure Kotlin so the sequence is JVM-testable.
 */
internal class TermuxServiceCore(
    val registry: SessionRegistry,
    private val logError: (String, Throwable) -> Unit = { _, _ -> },
    private val bootstrap: BootstrapInstaller? = null,
    private val stateStore: SessionStateStore? = null,
) {
    /**
     * Typed boundary handed to callers (Activity, future Binder). Session
     * creation consumes the bootstrap READY path only when the executable
     * actually lives under the installed prefix; absolute system programs
     * are never rewritten or blocked by bootstrap state.
     */
    val api: TermuxServiceApi = GatedServiceApi(registry, bootstrap)

    private var destroyed = false

    /** Last snapshot SUBMITTED to the worker (not a durability ack — the
     *  worker independently guarantees bounded retries to confirmation). */
    private var lastSubmitted: List<PersistedSession> = emptyList()
    private val persistence = stateStore?.let {
        SessionPersistenceWorker(it) { message, error -> logError(message, error) }
    }

    /** Restore barrier: nothing may create or deliver sessions until the
     *  persisted state has been applied on the owner thread (or determined
     *  empty/corrupt). Open from the start when there is no persistence. */
    private val restoreBarrier = java.util.concurrent.CountDownLatch(
        if (persistence == null) 0 else 1,
    )

    /** Starts an async load off the owner thread; the decoded records are
     *  applied on the owner thread via [runOnOwner], then the barrier opens
     *  and [afterRestore] runs (deterministic drain order). */
    fun restoreSessions(runOnOwner: (() -> Unit) -> Unit, afterRestore: () -> Unit = {}) {
        val worker = persistence
        if (worker == null) {
            restoreBarrier.countDown()
            return
        }
        worker.loadAsync { loaded ->
            runOnOwner {
                try {
                    registry.restoreSessions(loaded)
                    lastSubmitted = registry.snapshotForPersistence()
                } finally {
                    // The barrier must open and the post-restore hook must
                    // never be able to kill the owner thread or block the
                    // drive loop from starting.
                    restoreBarrier.countDown()
                    runCatching { afterRestore() }
                        .onFailure { logError("afterRestore hook failed", it) }
                }
            }
        }
    }

    /** Non-blocking RESTORING signal: session-creating calls fail fast with
     *  a retryable error until the barrier opens. No thread ever blocks on
     *  the barrier — queuing/retry is the caller's policy. */
    private fun restoringFailure(): AppShellResult.Failure? =
        if (restoreBarrier.count > 0) {
            AppShellResult.Failure(
                AppShellError(AppShellErrorCode.INTERNAL_FAILURE, retryable = true),
            )
        } else {
            null
        }

    private fun requireNotRestoring(): AppShellResult.Failure? = restoringFailure()

    /** Test hook: block until the restore barrier has opened. */
    internal fun awaitRestoreForTest() {
        restoreBarrier.await(5, java.util.concurrent.TimeUnit.SECONDS)
    }

    /** Owner-thread snapshot + handoff; the worker writes/fsyncs/retries. */
    fun persistIfChanged() {
        val worker = persistence ?: return
        val snapshot = registry.snapshotForPersistence()
        if (snapshot != lastSubmitted) {
            lastSubmitted = snapshot
            worker.submitLatest(snapshot)
        }
    }

    /** Bounded persistence flush for destroy; safe to call on the owner
     *  thread (waits on the worker, never does I/O itself). */
    fun flushPersistence(timeoutMs: Long): Boolean =
        persistence?.shutdown(timeoutMs) ?: true

    private inner class GatedServiceApi(
        private val delegate: TermuxServiceApi,
        private val bootstrap: BootstrapInstaller?,
    ) : TermuxServiceApi by delegate {
        override fun createSession(request: AppExecutionRequest): AppShellResult<SessionId> {
            requireNotRestoring()?.let { return it }
            if (bootstrap != null &&
                bootstrap.requiresBootstrapPath(request.executable) &&
                bootstrap.state() != BootstrapInstallState.READY
            ) {
                return AppShellResult.Failure(
                    AppShellError(AppShellErrorCode.BOOTSTRAP_UNAVAILABLE, retryable = true),
                )
            }
            return delegate.createSession(request)
        }

        override fun writeInput(id: SessionId, input: SessionInput): AppShellResult<Unit> {
            requireNotRestoring()?.let { return it }
            return delegate.writeInput(id, input)
        }

        override fun resize(id: SessionId, dimensions: TerminalDimensions): AppShellResult<Unit> {
            requireNotRestoring()?.let { return it }
            return delegate.resize(id, dimensions)
        }
    }

    /**
     * Deterministic shutdown, idempotent. Must run on the service owner
     * thread: cancels every session with SERVICE_SHUTDOWN, marks all for
     * close, then drives the registry until every engine is terminated and
     * closed exactly once. Sessions that never spawned are closed without
     * spawning. A single session's engine failure never blocks the recovery
     * of the remaining sessions.
     */
    @Synchronized
    fun destroy() {
        if (destroyed) return
        destroyed = true
        val snapshots = (registry.sessions() as AppShellResult.Success).value
        snapshots.forEach { snapshot ->
            registry.cancel(snapshot.id, CancellationReason.SERVICE_SHUTDOWN)
            registry.close(snapshot.id)
        }
        // Two passes: the first terminates running engines and polls their
        // exit; the second finalizes any entry that needed another drive.
        // Each pass is fault-isolated so one bad engine cannot skip the rest.
        runCatching { registry.driveAll() }
            .onFailure { logError("destroy drive pass 1 incomplete", it) }
        runCatching { registry.driveAll() }
            .onFailure { logError("destroy drive pass 2 incomplete", it) }
    }
}
