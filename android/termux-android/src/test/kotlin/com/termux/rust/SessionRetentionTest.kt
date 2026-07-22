package com.termux.rust

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** CLOSED/EXITED retention and purge contract. */
class SessionRetentionTest {
    @Test
    fun exited_engine_closes_immediately_while_banner_metadata_survives_until_ttl() {
        var now = 1_000L
        val engine = FakeEngine()
        val registry = SessionRegistry(clock = { now }, engineFactory = { _, _ -> engine })
        val id = (registry.createSession(request()) as AppShellResult.Success).value
        registry.drive(id)

        engine.pendingTermination = SessionTermination.ProcessExited(7)
        registry.drive(id)

        // Banner window: termination is readable immediately.
        val exited = snapshotOf(registry, id)
        assertEquals(SessionLifecycle.EXITED, exited.lifecycle)
        assertEquals(SessionTermination.ProcessExited(7), exited.termination)

        // Retention must release the native engine immediately. The banner
        // reads retained termination metadata and does not need the PTY open.
        registry.reapTerminals()
        val closed = snapshotOf(registry, id)
        assertEquals(SessionLifecycle.CLOSED, closed.lifecycle)
        assertEquals(SessionTermination.ProcessExited(7), closed.termination)
        assertEquals(1, engine.closeCount)

        // Closed entries survive the closed TTL, then are purged.
        now += SessionRegistry.DEFAULT_CLOSED_RETENTION_MS - 1
        registry.reapTerminals()
        assertIs<AppShellResult.Success<SessionSnapshot>>(registry.session(id))
        now += 1
        registry.reapTerminals()
        assertEquals(
            AppShellErrorCode.SESSION_NOT_FOUND,
            (registry.session(id) as AppShellResult.Failure).error.code,
        )
    }

    @Test
    fun purge_isolates_sessions_by_age_and_never_touches_live() {
        var now = 10_000L
        val engines = mutableListOf<FakeEngine>()
        val registry = SessionRegistry(clock = { now }, engineFactory = { _, _ -> FakeEngine().also(engines::add) })
        val old = (registry.createSession(request()) as AppShellResult.Success).value
        registry.drive(old)
        engines[0].pendingTermination = SessionTermination.ProcessExited(1)
        registry.drive(old)

        registry.reapTerminals()
        assertEquals(SessionLifecycle.CLOSED, snapshotOf(registry, old).lifecycle)

        // Age the first closed session to its TTL, then create a second one.
        now += SessionRegistry.DEFAULT_CLOSED_RETENTION_MS
        val fresh = (registry.createSession(request()) as AppShellResult.Success).value
        registry.drive(fresh)
        engines[1].pendingTermination = SessionTermination.ProcessExited(2)
        registry.drive(fresh)

        registry.reapTerminals()

        assertEquals(
            AppShellErrorCode.SESSION_NOT_FOUND,
            (registry.session(old) as AppShellResult.Failure).error.code,
        )
        // Fresh session closes in the same pass but starts a new retention
        // window, so only the older entry is purged.
        val freshSnapshot = snapshotOf(registry, fresh)
        assertEquals(SessionLifecycle.CLOSED, freshSnapshot.lifecycle)
        assertEquals(SessionTermination.ProcessExited(2), freshSnapshot.termination)
    }

    @Test
    fun repeated_reads_and_reap_cycles_are_deterministic() {
        var now = 0L
        val engine = FakeEngine()
        val registry = SessionRegistry(clock = { now }, engineFactory = { _, _ -> engine })
        val id = (registry.createSession(request()) as AppShellResult.Success).value
        registry.drive(id)
        engine.pendingTermination = SessionTermination.ProcessExited(0)
        registry.drive(id)

        repeat(5) { registry.reapTerminals() }
        assertEquals(1, engine.closeCount)
        assertEquals(SessionLifecycle.CLOSED, snapshotOf(registry, id).lifecycle)
    }

    private fun snapshotOf(registry: SessionRegistry, id: SessionId): SessionSnapshot =
        (registry.session(id) as AppShellResult.Success).value

    private fun request() = AppExecutionRequest(
        origin = RequestOrigin.Internal,
        executable = "/system/bin/sh",
        target = ExecutionTarget.TERMINAL_SESSION,
        terminalSize = TerminalDimensions(80, 24),
    )

    private class FakeEngine : SessionEngine {
        var pendingTermination: SessionTermination? = null
        var closeCount = 0
        override fun writeInput(input: SessionInput) = Unit
        override fun resize(dimensions: TerminalDimensions) = Unit
        override fun terminate() = Unit
        override fun pollTermination(): SessionTermination? = pendingTermination
        override fun renderFrame(): Pair<Long, ByteArray>? = null
        override fun close() {
            closeCount += 1
        }
    }
}
