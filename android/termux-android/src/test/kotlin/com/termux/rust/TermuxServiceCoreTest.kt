package com.termux.rust

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TermuxServiceCoreTest {
    @Test
    fun destroy_cancels_terminates_and_closes_every_session_exactly_once() {
        val engines = mutableListOf<FakeEngine>()
        val core = TermuxServiceCore(SessionRegistry(engineFactory = { _, _ -> FakeEngine().also(engines::add) }))
        val first = (core.api.createSession(request()) as AppShellResult.Success).value
        val second = (core.api.createSession(request()) as AppShellResult.Success).value

        core.registry.drive(first)
        core.registry.drive(second)
        assertEquals(2, engines.size)

        core.destroy()

        engines.forEach { engine ->
            assertEquals(1, engine.terminateCount)
            assertEquals(1, engine.closeCount)
        }
        listOf(first, second).forEach { id ->
            val snapshot = (core.api.session(id) as AppShellResult.Success).value
            assertEquals(SessionLifecycle.CLOSED, snapshot.lifecycle)
            assertEquals(
                SessionTermination.Cancelled(CancellationReason.SERVICE_SHUTDOWN),
                snapshot.termination,
            )
        }
    }

    @Test
    fun destroy_is_idempotent() {
        val engine = FakeEngine()
        val core = TermuxServiceCore(SessionRegistry(engineFactory = { _, _ -> engine }))
        val id = (core.api.createSession(request()) as AppShellResult.Success).value
        core.registry.drive(id)

        core.destroy()
        core.destroy()

        assertEquals(1, engine.terminateCount)
        assertEquals(1, engine.closeCount)
    }

    @Test
    fun destroy_covers_sessions_that_never_drove_without_spawning() {
        var spawnCount = 0
        val core = TermuxServiceCore(SessionRegistry(engineFactory = { _, _ ->
            spawnCount += 1
            FakeEngine()
        }))
        val id = (core.api.createSession(request()) as AppShellResult.Success).value

        // STARTING, engine not yet spawned: destroy must close without spawning.
        core.destroy()

        assertEquals(0, spawnCount)
        val snapshot = (core.api.session(id) as AppShellResult.Success).value
        assertEquals(SessionLifecycle.CLOSED, snapshot.lifecycle)
        assertEquals(
            SessionTermination.Cancelled(CancellationReason.SERVICE_SHUTDOWN),
            snapshot.termination,
        )
    }

    @Test
    fun api_is_the_typed_registry_boundary() {
        val core = TermuxServiceCore(SessionRegistry(engineFactory = { _, _ -> FakeEngine() }))
        assertIs<TermuxServiceApi>(core.api)
    }

    @Test
    fun one_bad_engine_never_blocks_recovery_of_the_rest() {
        val good = FakeEngine()
        val bad = FakeEngine(failOnClose = true)
        var spawnIndex = 0
        val core = TermuxServiceCore(SessionRegistry(engineFactory = { _, _ ->
            if (spawnIndex++ == 0) bad else good
        }))
        val first = (core.api.createSession(request()) as AppShellResult.Success).value
        val second = (core.api.createSession(request()) as AppShellResult.Success).value
        core.registry.drive(first)
        core.registry.drive(second)

        // Must not throw, and the healthy engine is still fully recovered.
        core.destroy()

        assertEquals(1, good.terminateCount)
        assertEquals(1, good.closeCount)
        assertEquals(1, bad.terminateCount)
    }

    private fun request() = AppExecutionRequest(
        origin = RequestOrigin.Internal,
        executable = "/system/bin/sh",
        target = ExecutionTarget.TERMINAL_SESSION,
        terminalSize = TerminalDimensions(80, 24),
    )

    private class FakeEngine(
        private val failOnClose: Boolean = false,
    ) : SessionEngine {
        var terminateCount = 0
        var closeCount = 0
        private var termination: SessionTermination? = null

        override fun writeInput(input: SessionInput) = Unit
        override fun resize(dimensions: TerminalDimensions) = Unit
        override fun terminate() {
            terminateCount += 1
            termination = SessionTermination.ProcessExited(137, signal = "SIGKILL")
        }
        override fun pollTermination(): SessionTermination? = termination
        override fun renderFrame(): Pair<Long, ByteArray>? = null
        override fun close() {
            if (failOnClose) {
                throw SessionEngineException(AppShellError(AppShellErrorCode.NATIVE_FAILURE, retryable = false))
            }
            closeCount += 1
        }
    }
}
