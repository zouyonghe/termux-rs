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
) {
    /**
     * Typed boundary handed to callers (Activity, future Binder). Session
     * creation consumes the bootstrap READY path only when the executable
     * actually lives under the installed prefix; absolute system programs
     * are never rewritten or blocked by bootstrap state.
     */
    val api: TermuxServiceApi = GatedServiceApi(registry, bootstrap)

    private var destroyed = false

    private class GatedServiceApi(
        private val delegate: TermuxServiceApi,
        private val bootstrap: BootstrapInstaller?,
    ) : TermuxServiceApi by delegate {
        override fun createSession(request: AppExecutionRequest): AppShellResult<SessionId> {
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
