package com.termux.rust

/**
 * Per-session engine driven exclusively on the service owner thread. This is
 * where Rust FFI will live (see .4); the registry never calls it from
 * Binder/caller threads.
 */
internal interface SessionEngine {
    fun writeInput(input: SessionInput)
    fun resize(dimensions: TerminalDimensions)
    fun terminate()
    fun pollTermination(): SessionTermination?

    /** Latest frame as (TRS1 version, raw snapshot bytes), or null. */
    fun renderFrame(): Pair<Long, ByteArray>?
    fun close()
}

/** Wraps any failure reported by a [SessionEngine] (native or adapter). */
internal class SessionEngineException(
    val error: AppShellError,
    cause: Throwable? = null,
) : RuntimeException("session engine failed: ${error.code}", cause)

/**
 * Service-owned multi-session registry. Binder-facing [TermuxServiceApi]
 * methods only mutate bookkeeping under a lock — they never run engine/FFI
 * code. All engine interaction happens inside [drive]/[driveAll], which the
 * service owner thread calls; that serialization is what makes the
 * single-threaded Rust FFI safe. Reentrant or concurrent drives are rejected
 * with [IllegalStateException] instead of risking a double spawn.
 *
 * IDs are allocated from a monotonic counter and never reused. Terminal
 * results are retained through EXITED into CLOSED. Cancel/close are
 * idempotent; Activity unbind has no hook here and never destroys sessions.
 */
internal class SessionRegistry(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val engineFactory: (AppExecutionRequest, SessionId) -> SessionEngine,
) : TermuxServiceApi {
    private val lock = Any()
    private val entries = LinkedHashMap<SessionId, Entry>()
    private var nextIdValue = 1L
    private var driving = false

    private class Entry(
        val id: SessionId,
        val request: AppExecutionRequest,
        val createdAtMs: Long,
        var engine: SessionEngine? = null,
        var lifecycle: SessionLifecycle = SessionLifecycle.STARTING,
        var termination: SessionTermination? = null,
        var closedAtMs: Long? = null,
        var cancellationReason: CancellationReason? = null,
        val pendingInputs: ArrayDeque<SessionInput> = ArrayDeque(),
        var pendingResize: TerminalDimensions? = null,
        var terminatePending: Boolean = false,
        var closePending: Boolean = false,
        var engineTerminated: Boolean = false,
        var engineClosed: Boolean = false,
        var frame: CachedSessionFrame? = null,
    ) {
        val live: Boolean
            get() = lifecycle == SessionLifecycle.STARTING ||
                lifecycle == SessionLifecycle.RUNNING ||
                lifecycle == SessionLifecycle.CANCELLING

        fun snapshot(): SessionSnapshot =
            SessionSnapshot(id, request.label, request.target, lifecycle, termination)
    }

    override fun createSession(request: AppExecutionRequest): AppShellResult<SessionId> =
        synchronized(lock) {
            if (entries.values.count { it.live } >= capacity) {
                return failure(AppShellErrorCode.SESSION_LIMIT_REACHED, retryable = true)
            }
            val id = SessionId(nextIdValue++)
            entries[id] = Entry(id, request, createdAtMs = clock())
            AppShellResult.Success(id)
        }

    override fun sessions(): AppShellResult<List<SessionSnapshot>> =
        synchronized(lock) { AppShellResult.Success(entries.values.map { it.snapshot() }) }

    override fun session(id: SessionId): AppShellResult<SessionSnapshot> =
        synchronized(lock) {
            entries[id]?.let { AppShellResult.Success(it.snapshot()) } ?: notFound()
        }

    override fun writeInput(id: SessionId, input: SessionInput): AppShellResult<Unit> =
        synchronized(lock) {
            val entry = entries[id] ?: return notFound()
            if (!entry.request.sessionMode.acceptsInput) {
                return failure(AppShellErrorCode.INVALID_REQUEST, retryable = false)
            }
            if (entry.lifecycle == SessionLifecycle.RUNNING || entry.lifecycle == SessionLifecycle.STARTING) {
                entry.pendingInputs.add(input)
            }
            AppShellResult.Success(Unit)
        }

    override fun resize(id: SessionId, dimensions: TerminalDimensions): AppShellResult<Unit> =
        synchronized(lock) {
            val entry = entries[id] ?: return notFound()
            if (!entry.request.sessionMode.acceptsResize) {
                return failure(AppShellErrorCode.INVALID_REQUEST, retryable = false)
            }
            if (entry.lifecycle == SessionLifecycle.RUNNING || entry.lifecycle == SessionLifecycle.STARTING) {
                entry.pendingResize = dimensions
            }
            AppShellResult.Success(Unit)
        }

    override fun cancel(id: SessionId, reason: CancellationReason): AppShellResult<Unit> =
        synchronized(lock) {
            val entry = entries[id] ?: return notFound()
            if (entry.live) {
                if (entry.cancellationReason == null) {
                    entry.cancellationReason = reason // first reason wins
                    entry.terminatePending = true
                    entry.lifecycle = SessionLifecycle.CANCELLING
                }
            }
            AppShellResult.Success(Unit)
        }

    override fun close(id: SessionId): AppShellResult<Unit> =
        synchronized(lock) {
            val entry = entries[id] ?: return notFound()
            if (entry.lifecycle != SessionLifecycle.CLOSED) {
                if (entry.live && entry.cancellationReason == null) {
                    entry.cancellationReason = CancellationReason.USER_REQUEST
                    entry.terminatePending = true
                    entry.lifecycle = SessionLifecycle.CANCELLING
                }
                entry.closePending = true
            }
            AppShellResult.Success(Unit)
        }

    override fun cachedFrame(id: SessionId, afterVersion: Long?): AppShellResult<CachedSessionFrame?> =
        synchronized(lock) {
            val entry = entries[id] ?: return notFound()
            val frame = entry.frame
            AppShellResult.Success(
                if (frame != null && (afterVersion == null || frame.version > afterVersion)) frame else null,
            )
        }

    fun summary(): RegistrySummary =
        synchronized(lock) {
            RegistrySummary(
                starting = entries.values.count { it.lifecycle == SessionLifecycle.STARTING },
                running = entries.values.count { it.lifecycle == SessionLifecycle.RUNNING },
                cancelling = entries.values.count { it.lifecycle == SessionLifecycle.CANCELLING },
                exited = entries.values.count { it.lifecycle == SessionLifecycle.EXITED },
            )
        }

    /** Removes CLOSED entries. Owner-thread housekeeping for the service. */
    fun purgeClosed() =
        synchronized(lock) {
            entries.values.removeAll { it.lifecycle == SessionLifecycle.CLOSED }
        }

    /** Owner-thread drive for every session. See [drive]. A failure in one
     *  session's engine work never skips the remaining sessions. */
    fun driveAll() {
        val ids = synchronized(lock) {
            check(!driving) { "reentrant or concurrent drive is forbidden" }
            driving = true
            entries.keys.toList()
        }
        var firstFailure: Throwable? = null
        try {
            ids.forEach { id ->
                synchronized(lock) { entries[id] }?.let { entry ->
                    try {
                        driveEntry(entry)
                    } catch (error: Throwable) {
                        if (firstFailure == null) firstFailure = error
                    }
                }
            }
        } finally {
            synchronized(lock) { driving = false }
        }
        firstFailure?.let { throw it }
    }

    /**
     * Owner-thread drive for one session: lazily creates the engine, applies
     * queued mutations, enforces the timeout, polls termination, refreshes
     * the cached frame, and finalizes close. Never call from Binder threads;
     * reentrant/concurrent calls fail fast with [IllegalStateException].
     */
    fun drive(id: SessionId) {
        val entry = synchronized(lock) {
            check(!driving) { "reentrant or concurrent drive is forbidden" }
            driving = true
            entries[id]
        }
        try {
            entry?.let(::driveEntry)
        } finally {
            synchronized(lock) { driving = false }
        }
    }

    private fun driveEntry(entry: Entry) {
        val work = prepareDrive(entry) ?: return
        work()
    }

    internal fun engineForTest(id: SessionId): SessionEngine? =
        synchronized(lock) { entries[id]?.engine }

    /** Captures entry state under the lock and returns the engine work to run
     *  outside it, so long FFI calls never block Binder-facing bookkeeping. */
    private fun prepareDrive(entry: Entry): (() -> Unit)? {
        synchronized(lock) {
            if (entry.lifecycle == SessionLifecycle.CLOSED) return null

            // Timeout is measured from ID allocation.
            val timeout = entry.request.timeout
            if (entry.live && entry.cancellationReason == null && timeout is ExecutionTimeout.After) {
                if (clock() - entry.createdAtMs >= timeout.milliseconds) {
                    entry.cancellationReason = CancellationReason.TIMEOUT
                    entry.terminatePending = true
                    entry.lifecycle = SessionLifecycle.CANCELLING
                }
            }
        }
        return { runEngineWork(entry) }
    }

    private fun runEngineWork(entry: Entry) {
        // Lazy engine creation: spawn failures become a Failed termination
        // instead of a Binder-thread crash, and free the capacity slot.
        // The spawn decision reads state under the lock, so a Binder-thread
        // close() racing this drive can never be missed.
        val shouldSpawn = synchronized(lock) {
            entry.engine == null && !entry.engineClosed && !entry.closePending
        }
        if (shouldSpawn) {
            val engine = try {
                engineFactory(entry.request, entry.id)
            } catch (error: Throwable) {
                synchronized(lock) {
                    entry.lifecycle = SessionLifecycle.EXITED
                    entry.termination = SessionTermination.Failed(
                        AppShellError(AppShellErrorCode.NATIVE_FAILURE, retryable = false),
                    )
                    entry.engineClosed = true
                }
                finalizeClose(entry)
                return
            }
            synchronized(lock) {
                entry.engine = engine
                if (entry.lifecycle == SessionLifecycle.STARTING) {
                    entry.lifecycle = SessionLifecycle.RUNNING
                }
            }
        }
        val engine = synchronized(lock) { entry.engine.takeIf { !entry.engineClosed } }

        if (engine != null) {
            try {
                val inputs = synchronized(lock) {
                    buildList {
                        while (entry.pendingInputs.isNotEmpty()) add(entry.pendingInputs.removeFirst())
                    }
                }
                inputs.forEach(engine::writeInput)
                val resize = synchronized(lock) { entry.pendingResize.also { entry.pendingResize = null } }
                resize?.let(engine::resize)

                val shouldTerminate = synchronized(lock) { entry.terminatePending && !entry.engineTerminated }
                if (shouldTerminate) {
                    engine.terminate()
                    synchronized(lock) { entry.engineTerminated = true }
                }

                val reported = engine.pollTermination()
                if (reported != null) {
                    synchronized(lock) {
                        if (entry.lifecycle != SessionLifecycle.EXITED) {
                            entry.lifecycle = SessionLifecycle.EXITED
                            entry.termination = resolveTermination(entry, reported)
                        }
                    }
                }

                val rawFrame = engine.renderFrame()
                if (rawFrame != null) {
                    synchronized(lock) {
                        entry.frame = CachedSessionFrame(entry.id, rawFrame.first, rawFrame.second)
                    }
                }
            } catch (error: Throwable) {
                // Any engine failure fails the session deterministically and
                // stops further engine calls; the entry stays closable.
                synchronized(lock) {
                    if (entry.lifecycle != SessionLifecycle.EXITED && entry.lifecycle != SessionLifecycle.CLOSED) {
                        entry.lifecycle = SessionLifecycle.EXITED
                        entry.termination = SessionTermination.Failed(
                            (error as? SessionEngineException)?.error
                                ?: AppShellError(AppShellErrorCode.NATIVE_FAILURE, retryable = false),
                        )
                    }
                    entry.engineClosed = true
                }
            }
        }

        finalizeClose(entry)
    }

    private fun resolveTermination(
        entry: Entry,
        reported: SessionTermination,
    ): SessionTermination = when (entry.cancellationReason) {
        CancellationReason.TIMEOUT -> {
            val timeout = entry.request.timeout
            if (timeout is ExecutionTimeout.After) {
                SessionTermination.TimedOut(timeout.milliseconds)
            } else {
                reported
            }
        }
        null -> reported
        else -> SessionTermination.Cancelled(entry.cancellationReason!!)
    }

    private fun finalizeClose(entry: Entry) {
        val closeEngine = synchronized(lock) {
            if (!entry.closePending || entry.lifecycle == SessionLifecycle.CLOSED) {
                false
            } else if (entry.engine == null) {
                // Engine never created (or spawn failed): go straight to
                // CLOSED, synthesizing the retained terminal result.
                if (entry.termination == null) {
                    entry.termination = resolveTermination(
                        entry,
                        SessionTermination.ProcessExited(137, signal = "SIGKILL"),
                    )
                }
                entry.lifecycle = SessionLifecycle.CLOSED
                entry.closedAtMs = clock()
                false
            } else if (entry.lifecycle == SessionLifecycle.EXITED && !entry.engineClosed) {
                true
            } else {
                false
            }
        }
        if (closeEngine) {
            entry.engine?.close()
            synchronized(lock) {
                entry.engineClosed = true
                entry.lifecycle = SessionLifecycle.CLOSED // termination retained
                entry.closedAtMs = clock()
            }
        }
    }

    /**
     * Owner-thread retention pass, driven by [TermuxService]'s drive loop:
     *
     * - EXITED entries are closed immediately to release native/PTY resources;
     *   their termination remains retained for the Activity exit banner
     * - CLOSED entries older than [closedRetentionMs] are purged from the
     *   registry entirely
     *
     * Live sessions are never touched; reaping is idempotent and uses the
     * injected clock, so it is deterministic under test.
     */
    fun reapTerminals(
        closedRetentionMs: Long = DEFAULT_CLOSED_RETENTION_MS,
    ) {
        synchronized(lock) {
            check(!driving) { "reentrant or concurrent drive is forbidden" }
            driving = true
        }
        try {
            val now = clock()
            val toClose = synchronized(lock) {
                entries.values.filter {
                    it.lifecycle == SessionLifecycle.EXITED &&
                        !it.closePending
                }.onEach { it.closePending = true }
            }
            toClose.forEach { entry ->
                try {
                    driveEntry(entry)
                } catch (error: Throwable) {
                    // One failing engine never blocks reaping the rest.
                }
            }
            synchronized(lock) {
                entries.values.removeAll {
                    it.lifecycle == SessionLifecycle.CLOSED &&
                        it.closedAtMs?.let { t -> now - t >= closedRetentionMs } == true
                }
            }
        } finally {
            synchronized(lock) { driving = false }
        }
    }

    /**
     * Restores persisted session metadata after a service restart. Live-at-
     * crash sessions become observable terminal failures — a native child
     * never survives process death, and nothing is ever respawned or
     * replayed. CLOSED sessions come back with their termination retained.
     * Idempotent: a restore into a non-empty registry is a no-op, and fresh
     * sessions always get ids beyond the restored ones.
     *
     * Must run on the service owner thread before the first drive pass.
     */
    fun restoreSessions(persisted: List<PersistedSession>) {
        synchronized(lock) {
            var maxId = 0L
            persisted.forEach { session ->
                // A record that violates the contract is skipped, never
                // crashes: store load already validates, this is the second
                // line of defense. Existing ids are never overwritten —
                // under the restore barrier this merge is defense-in-depth
                // only, not the conflict-resolution mechanism.
                val entry = runCatching { restoreEntry(session) }.getOrNull() ?: return@forEach
                if (entries.containsKey(entry.id)) return@forEach
                entries[entry.id] = entry
                maxId = maxOf(maxId, session.id)
            }
            nextIdValue = maxOf(nextIdValue, maxId + 1)
        }
    }

    private fun restoreEntry(session: PersistedSession): Entry? {
        val request = AppExecutionRequest(
            origin = RequestOrigin.Internal,
            executable = session.executable,
            arguments = session.arguments,
            workingDirectory = session.workingDirectory,
            target = session.target,
            label = session.label,
            terminalSize = session.terminalSize
                ?: if (session.target == ExecutionTarget.TERMINAL_SESSION) {
                    TerminalDimensions(80, 24)
                } else {
                    null
                },
        )
        val entry = Entry(SessionId(session.id), request, session.createdAtMs)
        when (session.lifecycle) {
            PersistedLifecycle.LIVE -> {
                // The child died with the process: deterministic, observable,
                // idempotent terminal failure. engineClosed blocks any spawn.
                entry.lifecycle = SessionLifecycle.EXITED
                entry.termination = SessionTermination.Failed(
                    AppShellError(AppShellErrorCode.INTERNAL_FAILURE, retryable = false),
                )
                entry.engineClosed = true
                entry.closePending = true
            }
            PersistedLifecycle.CLOSED -> {
                val termination = session.termination?.toTermination() ?: return null
                entry.lifecycle = SessionLifecycle.CLOSED
                entry.closedAtMs = session.closedAtMs ?: clock()
                entry.termination = termination
                entry.engineClosed = true
            }
        }
        return entry
    }

    /** Current sessions as rebuild-safe metadata for [SessionStateStore]. */
    fun snapshotForPersistence(): List<PersistedSession> =
        synchronized(lock) {
            entries.values.map { entry ->
                val exited = !entry.live
                PersistedSession(
                    id = entry.id.value,
                    executable = entry.request.executable,
                    arguments = entry.request.arguments,
                    workingDirectory = entry.request.workingDirectory,
                    label = entry.request.label,
                    target = entry.request.target,
                    terminalSize = entry.request.terminalSize,
                    lifecycle = if (exited) PersistedLifecycle.CLOSED else PersistedLifecycle.LIVE,
                    termination = entry.termination?.toPersisted(),
                    createdAtMs = entry.createdAtMs,
                    closedAtMs = entry.closedAtMs,
                )
            }
        }

    internal fun requestForTest(id: SessionId): AppExecutionRequest? =
        synchronized(lock) { entries[id]?.request }

    private fun notFound(): AppShellResult.Failure =
        failure(AppShellErrorCode.SESSION_NOT_FOUND, retryable = false)

    private fun failure(code: AppShellErrorCode, retryable: Boolean): AppShellResult.Failure =
        AppShellResult.Failure(AppShellError(code, retryable))

    internal companion object {
        const val DEFAULT_CAPACITY = 32

        /** How long a CLOSED session's termination remains queryable before
         *  the entry is purged. */
        const val DEFAULT_CLOSED_RETENTION_MS = 300_000L
    }
}

private fun SessionTermination.toPersisted(): PersistedTermination = when (this) {
    is SessionTermination.ProcessExited ->
        PersistedTermination(PersistedTerminationKind.EXITED, code.toString())
    is SessionTermination.Cancelled ->
        PersistedTermination(PersistedTerminationKind.CANCELLED, reason.name)
    is SessionTermination.TimedOut ->
        PersistedTermination(PersistedTerminationKind.TIMEOUT, timeoutMilliseconds.toString())
    is SessionTermination.Failed ->
        PersistedTermination(PersistedTerminationKind.FAILED, error.code.name)
}

private fun PersistedTermination.toTermination(): SessionTermination = when (kind) {
    PersistedTerminationKind.EXITED -> SessionTermination.ProcessExited(value.toInt())
    PersistedTerminationKind.CANCELLED -> SessionTermination.Cancelled(CancellationReason.valueOf(value))
    PersistedTerminationKind.TIMEOUT -> SessionTermination.TimedOut(value.toLong())
    PersistedTerminationKind.FAILED ->
        SessionTermination.Failed(AppShellError(AppShellErrorCode.valueOf(value), retryable = false))
}
