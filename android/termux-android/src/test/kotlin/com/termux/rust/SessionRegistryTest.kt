package com.termux.rust

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionRegistryTest {
    @Test
    fun creates_stable_unique_ids_with_independent_lifecycles() {
        val registry = SessionRegistry(engineFactory = { _, _ -> FakeEngine() })
        val first = (registry.createSession(terminalRequest()) as AppShellResult.Success).value
        val second = (registry.createSession(terminalRequest()) as AppShellResult.Success).value

        assertNotEquals(first, second)
        assertEquals(SessionLifecycle.STARTING, snapshotOf(registry, first).lifecycle)

        registry.drive(first)
        assertEquals(SessionLifecycle.RUNNING, snapshotOf(registry, first).lifecycle)
        // The other session is unaffected.
        assertEquals(SessionLifecycle.STARTING, snapshotOf(registry, second).lifecycle)
    }

    @Test
    fun rejects_sessions_beyond_capacity_and_unknown_ids() {
        val registry = SessionRegistry(capacity = 1, engineFactory = { _, _ -> FakeEngine() })
        assertIs<AppShellResult.Success<SessionId>>(registry.createSession(terminalRequest()))

        val overflow = registry.createSession(terminalRequest())
        assertIs<AppShellResult.Failure>(overflow)
        assertEquals(AppShellErrorCode.SESSION_LIMIT_REACHED, overflow.error.code)

        val missing = SessionId(999)
        assertEquals(AppShellErrorCode.SESSION_NOT_FOUND, (registry.session(missing) as AppShellResult.Failure).error.code)
        assertEquals(AppShellErrorCode.SESSION_NOT_FOUND, (registry.writeInput(missing, SessionInput.of(byteArrayOf(1))) as AppShellResult.Failure).error.code)
        assertEquals(AppShellErrorCode.SESSION_NOT_FOUND, (registry.resize(missing, TerminalDimensions(1, 1)) as AppShellResult.Failure).error.code)
        assertEquals(AppShellErrorCode.SESSION_NOT_FOUND, (registry.cancel(missing, CancellationReason.USER_REQUEST) as AppShellResult.Failure).error.code)
        assertEquals(AppShellErrorCode.SESSION_NOT_FOUND, (registry.close(missing) as AppShellResult.Failure).error.code)
        assertEquals(AppShellErrorCode.SESSION_NOT_FOUND, (registry.cachedFrame(missing) as AppShellResult.Failure).error.code)
    }

    @Test
    fun never_reuses_ids_of_closed_sessions() {
        val registry = SessionRegistry(capacity = 2, engineFactory = { _, _ -> FakeEngine() })
        val first = (registry.createSession(terminalRequest()) as AppShellResult.Success).value
        registry.drive(first)
        registry.cancel(first, CancellationReason.USER_REQUEST)
        registry.drive(first)
        registry.close(first)
        registry.drive(first)
        assertEquals(SessionLifecycle.CLOSED, snapshotOf(registry, first).lifecycle)

        val next = (registry.createSession(terminalRequest()) as AppShellResult.Success).value
        assertNotEquals(first, next)
    }

    @Test
    fun enqueues_input_and_resize_until_the_owner_thread_drives() {
        val engine = FakeEngine()
        val registry = SessionRegistry(engineFactory = { _, _ -> engine })
        val id = (registry.createSession(terminalRequest()) as AppShellResult.Success).value

        registry.writeInput(id, SessionInput.of(byteArrayOf(1, 2)))
        registry.resize(id, TerminalDimensions(100, 40))
        // Binder-facing calls must not touch the engine (no FFI on caller thread).
        assertEquals(0, engine.inputs.size)
        assertEquals(0, engine.resizeCount)

        registry.drive(id)
        assertEquals(listOf(SessionInput.of(byteArrayOf(1, 2))), engine.inputs)
        assertEquals(1, engine.resizeCount)
        assertEquals(TerminalDimensions(100, 40), engine.lastDimensions)
    }

    @Test
    fun rejects_input_and_resize_for_noninteractive_app_shell_sessions() {
        val registry = SessionRegistry(engineFactory = { _, _ -> FakeEngine() })
        val id = (registry.createSession(appShellRequest()) as AppShellResult.Success).value

        assertEquals(
            AppShellErrorCode.INVALID_REQUEST,
            (registry.writeInput(id, SessionInput.of(byteArrayOf(1))) as AppShellResult.Failure).error.code,
        )
        assertEquals(
            AppShellErrorCode.INVALID_REQUEST,
            (registry.resize(id, TerminalDimensions(80, 24)) as AppShellResult.Failure).error.code,
        )
    }

    @Test
    fun cached_frames_follow_latest_and_after_version_semantics() {
        val engine = FakeEngine()
        val registry = SessionRegistry(engineFactory = { _, _ -> engine })
        val id = (registry.createSession(terminalRequest()) as AppShellResult.Success).value

        // No frame cached before the owner thread drives.
        assertNull((registry.cachedFrame(id) as AppShellResult.Success).value)

        engine.nextFrame = byteArrayOf(1, 2)
        engine.nextFrameVersion = 7
        registry.drive(id)

        val frame = (registry.cachedFrame(id) as AppShellResult.Success).value!!
        assertEquals(7, frame.version)
        assertTrue(frame.payload.contentEquals(byteArrayOf(1, 2)))

        // afterVersion: same or newer yields empty; older yields the frame.
        assertNull((registry.cachedFrame(id, afterVersion = 7) as AppShellResult.Success).value)
        assertNull((registry.cachedFrame(id, afterVersion = 8) as AppShellResult.Success).value)
        assertEquals(7, (registry.cachedFrame(id, afterVersion = 6) as AppShellResult.Success).value!!.version)

        // Frames are immutable values: mutating a read cannot corrupt the cache.
        frame.payload[0] = 99
        assertTrue(
            (registry.cachedFrame(id) as AppShellResult.Success).value!!.payload.contentEquals(byteArrayOf(1, 2)),
        )
    }

    @Test
    fun retains_terminal_result_through_exited_and_closed() {
        val engine = FakeEngine()
        val registry = SessionRegistry(engineFactory = { _, _ -> engine })
        val id = (registry.createSession(terminalRequest()) as AppShellResult.Success).value

        registry.drive(id)
        engine.pendingTermination = SessionTermination.ProcessExited(code = 3)
        registry.drive(id)

        val exited = snapshotOf(registry, id)
        assertEquals(SessionLifecycle.EXITED, exited.lifecycle)
        assertEquals(SessionTermination.ProcessExited(3), exited.termination)

        registry.close(id)
        registry.drive(id)
        val closed = snapshotOf(registry, id)
        assertEquals(SessionLifecycle.CLOSED, closed.lifecycle)
        assertEquals(SessionTermination.ProcessExited(3), closed.termination)
        assertEquals(1, engine.closeCount)
    }

    @Test
    fun cancel_and_close_are_idempotent_and_record_the_first_reason() {
        val engine = FakeEngine()
        val registry = SessionRegistry(engineFactory = { _, _ -> engine })
        val id = (registry.createSession(terminalRequest()) as AppShellResult.Success).value
        registry.drive(id)

        assertIs<AppShellResult.Success<*>>(registry.cancel(id, CancellationReason.USER_REQUEST))
        assertIs<AppShellResult.Success<*>>(registry.cancel(id, CancellationReason.SERVICE_SHUTDOWN))
        registry.drive(id)
        // First reason wins; engine terminated exactly once.
        assertEquals(SessionTermination.Cancelled(CancellationReason.USER_REQUEST), snapshotOf(registry, id).termination)
        assertEquals(1, engine.terminateCount)

        // Cancel after exit is a no-op success; close is idempotent too.
        assertIs<AppShellResult.Success<*>>(registry.cancel(id, CancellationReason.USER_REQUEST))
        assertIs<AppShellResult.Success<*>>(registry.close(id))
        assertIs<AppShellResult.Success<*>>(registry.close(id))
        registry.drive(id)
        registry.drive(id)
        assertEquals(1, engine.terminateCount)
        assertEquals(1, engine.closeCount)
    }

    @Test
    fun timeout_starts_at_id_allocation_and_terminates_with_timed_out() {
        var now = 1_000L
        val engine = FakeEngine()
        val registry = SessionRegistry(clock = { now }, engineFactory = { _, _ -> engine })
        val id = (registry.createSession(terminalRequest(timeout = ExecutionTimeout.After(500))) as AppShellResult.Success).value

        registry.drive(id)
        assertEquals(SessionLifecycle.RUNNING, snapshotOf(registry, id).lifecycle)

        now += 600
        registry.drive(id)
        assertEquals(SessionLifecycle.EXITED, snapshotOf(registry, id).lifecycle)
        assertEquals(SessionTermination.TimedOut(500), snapshotOf(registry, id).termination)
        assertEquals(1, engine.terminateCount)
    }

    @Test
    fun engine_factory_failure_marks_session_failed_and_frees_capacity() {
        val registry = SessionRegistry(capacity = 1, engineFactory = { _, _ -> error("native spawn failed") })
        val id = (registry.createSession(terminalRequest()) as AppShellResult.Success).value

        registry.drive(id)
        val snapshot = snapshotOf(registry, id)
        assertEquals(SessionLifecycle.EXITED, snapshot.lifecycle)
        assertIs<SessionTermination.Failed>(snapshot.termination)
        assertEquals(AppShellErrorCode.NATIVE_FAILURE, (snapshot.termination as SessionTermination.Failed).error.code)

        // Capacity is freed once the session is no longer live.
        assertIs<AppShellResult.Success<SessionId>>(registry.createSession(terminalRequest()))
    }

    @Test
    fun summary_counts_live_states_for_foreground_tracking() {
        val registry = SessionRegistry(engineFactory = { _, _ -> FakeEngine() })
        val id = (registry.createSession(terminalRequest()) as AppShellResult.Success).value

        assertEquals(RegistrySummary(starting = 1), registry.summary())
        registry.drive(id)
        assertEquals(RegistrySummary(running = 1), registry.summary())

        (registry.engineForTest(id) as FakeEngine).pendingTermination = SessionTermination.ProcessExited(0)
        registry.drive(id)
        assertEquals(RegistrySummary(exited = 1), registry.summary())
        assertTrue(!registry.summary().requiresForeground)
    }

    @Test
    fun reentrant_drive_is_rejected_and_never_double_spawns() {
        var spawnCount = 0
        lateinit var registry: SessionRegistry
        registry = SessionRegistry(engineFactory = { _, _ ->
            spawnCount += 1
            if (spawnCount == 1) {
                // Engine creation happens on the owner thread; a reentrant
                // drive from inside it must fail loudly instead of double-spawning.
                assertFailsWith<IllegalStateException> { registry.drive(SessionId(1)) }
            }
            FakeEngine()
        })
        registry.createSession(terminalRequest())

        registry.drive(SessionId(1))
        assertEquals(1, spawnCount)
        assertEquals(SessionLifecycle.RUNNING, snapshotOf(registry, SessionId(1)).lifecycle)
    }

    @Test
    fun close_before_first_drive_never_spawns_and_retains_cancelled_result() {
        var spawnCount = 0
        val registry = SessionRegistry(engineFactory = { _, _ ->
            spawnCount += 1
            FakeEngine()
        })
        val id = (registry.createSession(terminalRequest()) as AppShellResult.Success).value

        registry.close(id)
        registry.drive(id)

        assertEquals(0, spawnCount)
        val snapshot = snapshotOf(registry, id)
        assertEquals(SessionLifecycle.CLOSED, snapshot.lifecycle)
        assertEquals(SessionTermination.Cancelled(CancellationReason.USER_REQUEST), snapshot.termination)
    }

    @Test
    fun purge_closed_removes_only_terminal_entries() {
        val registry = SessionRegistry(engineFactory = { _, _ -> FakeEngine() })
        val closedId = (registry.createSession(terminalRequest()) as AppShellResult.Success).value
        val liveId = (registry.createSession(terminalRequest()) as AppShellResult.Success).value

        registry.close(closedId)
        registry.drive(closedId)
        assertEquals(SessionLifecycle.CLOSED, snapshotOf(registry, closedId).lifecycle)

        registry.purgeClosed()

        assertEquals(AppShellErrorCode.SESSION_NOT_FOUND, (registry.session(closedId) as AppShellResult.Failure).error.code)
        assertEquals(SessionLifecycle.STARTING, snapshotOf(registry, liveId).lifecycle)
    }

    @Test
    fun engine_failures_mark_session_failed_and_stop_further_engine_calls() {
        val engine = FakeEngine()
        engine.failOnWrite = true
        val registry = SessionRegistry(engineFactory = { _, _ -> engine })
        val id = (registry.createSession(terminalRequest()) as AppShellResult.Success).value

        registry.writeInput(id, SessionInput.of(byteArrayOf(1)))
        registry.drive(id)

        val snapshot = snapshotOf(registry, id)
        assertEquals(SessionLifecycle.EXITED, snapshot.lifecycle)
        assertIs<SessionTermination.Failed>(snapshot.termination)
        assertEquals(AppShellErrorCode.NATIVE_FAILURE, (snapshot.termination as SessionTermination.Failed).error.code)

        val callsBefore = engine.totalCalls()
        registry.drive(id)
        assertEquals(callsBefore, engine.totalCalls())
    }

    private fun snapshotOf(registry: SessionRegistry, id: SessionId): SessionSnapshot =
        (registry.session(id) as AppShellResult.Success).value

    private fun terminalRequest(timeout: ExecutionTimeout = ExecutionTimeout.Unlimited) = AppExecutionRequest(
        origin = RequestOrigin.Internal,
        executable = "/system/bin/sh",
        target = ExecutionTarget.TERMINAL_SESSION,
        terminalSize = TerminalDimensions(80, 24),
        timeout = timeout,
    )

    private fun appShellRequest() = AppExecutionRequest(
        origin = RequestOrigin.Internal,
        executable = "/system/bin/true",
        target = ExecutionTarget.APP_SHELL,
    )

    private class FakeEngine : SessionEngine {
        val inputs = mutableListOf<SessionInput>()
        var resizeCount = 0
        var lastDimensions: TerminalDimensions? = null
        var terminateCount = 0
        var closeCount = 0
        var pollCount = 0
        var renderCount = 0
        var pendingTermination: SessionTermination? = null
        var nextFrame: ByteArray? = null
        var nextFrameVersion = 0L
        var failOnWrite = false

        fun totalCalls() = inputs.size + resizeCount + terminateCount + closeCount + pollCount + renderCount

        override fun writeInput(input: SessionInput) {
            if (failOnWrite) throw SessionEngineException(AppShellError(AppShellErrorCode.NATIVE_FAILURE, retryable = false))
            inputs += input
        }
        override fun resize(dimensions: TerminalDimensions) {
            resizeCount += 1
            lastDimensions = dimensions
        }
        override fun terminate() {
            terminateCount += 1
            if (pendingTermination == null) {
                pendingTermination = SessionTermination.ProcessExited(137, signal = "SIGKILL")
            }
        }
        override fun pollTermination(): SessionTermination? {
            pollCount += 1
            return pendingTermination
        }
        override fun renderFrame(): Pair<Long, ByteArray>? {
            renderCount += 1
            return nextFrame?.let { nextFrameVersion to it }
        }
        override fun close() {
            closeCount += 1
        }
    }
}
