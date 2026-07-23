package com.termux.rust

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SessionRestoreTest {
    @Test
    fun live_sessions_become_observable_terminal_failures_never_respawns() {
        val registry = SessionRegistry(engineFactory = { _, _ -> error("must never spawn") })

        registry.restoreSessions(listOf(live(id = 5), closed(id = 6, code = 3)))

        val restored = snapshotOf(registry, SessionId(5))
        assertEquals(SessionLifecycle.EXITED, restored.lifecycle)
        val termination = restored.termination
        assertIs<SessionTermination.Failed>(termination)
        assertEquals(AppShellErrorCode.INTERNAL_FAILURE, termination.error.code)
        assertTrue(!termination.error.retryable)

        // Drive passes must never spawn an engine for the restored entry.
        registry.drive(SessionId(5))
        registry.drive(SessionId(5))
    }

    @Test
    fun closed_sessions_restore_metadata_with_retained_termination() {
        val registry = SessionRegistry(engineFactory = { _, _ -> error("must never spawn") })

        registry.restoreSessions(listOf(closed(id = 2, code = 7)))

        val restored = snapshotOf(registry, SessionId(2))
        assertEquals(SessionLifecycle.CLOSED, restored.lifecycle)
        assertEquals(SessionTermination.ProcessExited(7), restored.termination)
        assertEquals("/system/bin/sh", restoredSnapshotExecutable(registry, SessionId(2)))
    }

    @Test
    fun restore_merges_without_duplicating_or_overwriting() {
        val registry = SessionRegistry(engineFactory = { _, _ -> FakeEngine() })
        val sessions = listOf(closed(id = 1, code = 0), closed(id = 2, code = 1))

        registry.restoreSessions(sessions)
        registry.restoreSessions(sessions)
        // Merge (defense-in-depth): new ids are added once; existing ids are
        // never duplicated or overwritten.
        registry.restoreSessions(listOf(closed(id = 1, code = 0), closed(id = 9, code = 2)))

        val snapshots = (registry.sessions() as AppShellResult.Success).value
        assertEquals(3, snapshots.size)
        assertEquals(3, snapshots.map { it.id }.toSet().size)
        assertEquals(SessionId(9), snapshots.single { it.id.value > 2 }.id)
        // The original id=1 record keeps its original termination.
        assertEquals(SessionTermination.ProcessExited(0), snapshotOf(registry, SessionId(1)).termination)
    }

    @Test
    fun fresh_sessions_get_ids_beyond_restored_ones() {
        val registry = SessionRegistry(engineFactory = { _, _ -> FakeEngine() })
        registry.restoreSessions(listOf(closed(id = 41, code = 0)))

        val fresh = (registry.createSession(request()) as AppShellResult.Success).value
        assertNotEquals(SessionId(41), fresh)
        assertTrue(fresh.value > 41)
    }

    @Test
    fun restored_closed_sessions_age_out_through_normal_retention() {
        var now = 10_000L
        val registry = SessionRegistry(clock = { now }, engineFactory = { _, _ -> FakeEngine() })
        registry.restoreSessions(listOf(closed(id = 1, code = 0, closedAtMs = 5_000L)))

        registry.reapTerminals()
        assertIs<AppShellResult.Success<SessionSnapshot>>(registry.session(SessionId(1)))

        now += SessionRegistry.DEFAULT_CLOSED_RETENTION_MS
        registry.reapTerminals()
        assertEquals(
            AppShellErrorCode.SESSION_NOT_FOUND,
            (registry.session(SessionId(1)) as AppShellResult.Failure).error.code,
        )
    }

    private fun live(id: Long) = PersistedSession(
        id = id,
        executable = "/system/bin/sh",
        arguments = listOf("-c", "sleep 100"),
        workingDirectory = null,
        label = null,
        target = ExecutionTarget.TERMINAL_SESSION,
        terminalSize = TerminalDimensions(80, 24),
        lifecycle = PersistedLifecycle.LIVE,
        termination = null,
        createdAtMs = 0L,
        closedAtMs = null,
    )

    private fun closed(id: Long, code: Int, closedAtMs: Long = 2_000L) = PersistedSession(
        id = id,
        executable = "/system/bin/sh",
        arguments = emptyList(),
        workingDirectory = null,
        label = null,
        target = ExecutionTarget.TERMINAL_SESSION,
        terminalSize = TerminalDimensions(80, 24),
        lifecycle = PersistedLifecycle.CLOSED,
        termination = PersistedTermination(PersistedTerminationKind.EXITED, code.toString()),
        createdAtMs = 1_000L,
        closedAtMs = closedAtMs,
    )

    private fun snapshotOf(registry: SessionRegistry, id: SessionId): SessionSnapshot =
        (registry.session(id) as AppShellResult.Success).value

    private fun restoredSnapshotExecutable(registry: SessionRegistry, id: SessionId): String =
        registry.requestForTest(id)!!.executable

    private fun request() = AppExecutionRequest(
        origin = RequestOrigin.Internal,
        executable = "/system/bin/sh",
        target = ExecutionTarget.TERMINAL_SESSION,
        terminalSize = TerminalDimensions(80, 24),
    )

    private class FakeEngine : SessionEngine {
        override fun writeInput(input: SessionInput) = Unit
        override fun resize(dimensions: TerminalDimensions) = Unit
        override fun terminate() = Unit
        override fun pollTermination(): SessionTermination? = null
        override fun renderFrame(): Pair<Long, ByteArray>? = null
        override fun close() = Unit
    }
}
