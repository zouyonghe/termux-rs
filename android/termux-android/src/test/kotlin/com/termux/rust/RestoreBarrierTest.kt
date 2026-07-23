package com.termux.rust

import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Restore barrier + exit banner session-affinity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreBarrierTest {
    @Test
    fun pending_command_early_create_and_persisted_sessions_all_survive() {
        val storeFile = java.io.File.createTempFile("barrier", ".bin").apply { delete() }
        val store = SessionStateStore(storeFile)
        store.save(listOf(persisted(id = 7), persisted(id = 8)))

        PendingRunCommands.clear()
        PendingRunCommands.enqueue(runCommandRequest())
        val core = TermuxServiceCore(
            SessionRegistry(engineFactory = { _, _ -> FakeEngine() }),
            stateStore = store,
        )
        val applied = CountDownLatch(1)
        core.restoreSessions({ apply ->
            apply()
            applied.countDown()
        }) {
            // deterministic post-restore drain
            PendingRunCommands.drain().forEach { core.api.createSession(it) }
        }

        // Early binder create gets a fast retryable RESTORING failure (no
        // loss, no block); the caller retries after restore and succeeds.
        val early = core.api.createSession(terminalRequest())
        val earlyFailure = kotlin.test.assertIs<AppShellResult.Failure>(early)
        assertTrue(earlyFailure.error.retryable)
        assertTrue(applied.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val earlyId = (core.api.createSession(terminalRequest()) as AppShellResult.Success).value

        val ids = (core.api.sessions() as AppShellResult.Success).value.map { it.id.value }
        // persisted 7,8 + drained run-command + early create: all present,
        // unique, nothing lost or duplicated.
        assertEquals(4, ids.size)
        assertEquals(ids.toSet().size, ids.size)
        assertTrue(7L in ids && 8L in ids)
        assertTrue(earlyId.value !in setOf(7L, 8L))
    }

    @Test
    fun attaching_a_new_session_clears_the_old_exit_banner() {
        val activity = Robolectric.buildActivity(TerminalActivity::class.java).get()
        val api = FakeApi()
        activity.api = api
        activity.sessionId = SessionId(1)
        activity.exitBanner = "\n[process exited with code 7]"

        // Old session is gone (purged): attach must create a NEW session and
        // drop the stale banner.
        activity.attachSession()

        assertEquals(SessionId(2), activity.sessionId)
        assertNull(activity.exitBanner)
    }

    private fun persisted(id: Long) = PersistedSession(
        id = id,
        executable = "/system/bin/sh",
        arguments = emptyList(),
        workingDirectory = null,
        label = null,
        target = ExecutionTarget.TERMINAL_SESSION,
        terminalSize = TerminalDimensions(80, 24),
        lifecycle = PersistedLifecycle.CLOSED,
        termination = PersistedTermination(PersistedTerminationKind.EXITED, "0"),
        createdAtMs = 0L,
        closedAtMs = 1L,
    )

    private fun runCommandRequest() = AppExecutionRequest(
        origin = RequestOrigin.ExternalRunCommand(caller = null),
        executable = "/system/bin/true",
        target = ExecutionTarget.APP_SHELL,
    )

    private fun terminalRequest() = AppExecutionRequest(
        origin = RequestOrigin.Internal,
        executable = "/system/bin/sh",
        target = ExecutionTarget.TERMINAL_SESSION,
        terminalSize = TerminalDimensions(80, 24),
    )

    private class FakeApi : TermuxServiceApi {
        override fun createSession(request: AppExecutionRequest) = AppShellResult.Success(SessionId(2))
        override fun sessions(): AppShellResult<List<SessionSnapshot>> = AppShellResult.Success(emptyList())
        override fun session(id: SessionId): AppShellResult<SessionSnapshot> = AppShellResult.Failure(
            AppShellError(AppShellErrorCode.SESSION_NOT_FOUND, retryable = false),
        )
        override fun writeInput(id: SessionId, input: SessionInput) = AppShellResult.Success(Unit)
        override fun resize(id: SessionId, dimensions: TerminalDimensions) = AppShellResult.Success(Unit)
        override fun cancel(id: SessionId, reason: CancellationReason) = AppShellResult.Success(Unit)
        override fun close(id: SessionId) = AppShellResult.Success(Unit)
        override fun cachedFrame(id: SessionId, afterVersion: Long?): AppShellResult<CachedSessionFrame?> =
            AppShellResult.Success(null)
    }

    private class FakeEngine : SessionEngine {
        override fun writeInput(input: SessionInput) = Unit
        override fun resize(dimensions: TerminalDimensions) = Unit
        override fun terminate() = Unit
        override fun pollTermination(): SessionTermination? = null
        override fun renderFrame(): Pair<Long, ByteArray>? = null
        override fun close() = Unit
    }
}
