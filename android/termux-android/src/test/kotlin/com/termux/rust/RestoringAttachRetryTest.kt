package com.termux.rust

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoringAttachRetryTest {
    @Test
    fun create_session_fails_fast_while_restoring_without_blocking() {
        val storeFile = java.io.File.createTempFile("restoring", ".bin").apply { delete() }
        SessionStateStore(storeFile).save(listOf(persisted(id = 1)))
        val core = TermuxServiceCore(
            SessionRegistry(engineFactory = { _, _ -> FakeEngine() }),
            stateStore = SessionStateStore(storeFile),
        )

        val started = System.nanoTime()
        val result = core.api.createSession(terminalRequest())
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        val failure = assertIs<AppShellResult.Failure>(result)
        assertEquals(AppShellErrorCode.INTERNAL_FAILURE, failure.error.code)
        assertTrue(failure.error.retryable)
        assertTrue(elapsedMs < 100, "createSession blocked ${elapsedMs}ms")
        assertTrue((core.api.sessions() as AppShellResult.Success).value.isEmpty())
    }

    @Test
    fun attach_retry_succeeds_once_restore_completes_and_creates_exactly_one_session() {
        val storeFile = java.io.File.createTempFile("restoring", ".bin").apply { delete() }
        SessionStateStore(storeFile).save(listOf(persisted(id = 1)))
        val core = TermuxServiceCore(
            SessionRegistry(engineFactory = { _, _ -> FakeEngine() }),
            stateStore = SessionStateStore(storeFile),
        )
        val activity = Robolectric.buildActivity(TerminalActivity::class.java).get()
        activity.api = core.api

        val restoreDone = CountDownLatch(1)
        core.restoreSessions({ apply -> apply() }) { restoreDone.countDown() }

        activity.attachSession()
        assertNull(activity.sessionId) // RESTORING: nothing created yet

        assertTrue(restoreDone.await(5, TimeUnit.SECONDS))
        // The queued retry must now succeed; calling attach again must NOT
        // create a second session.
        activity.attachSession()
        activity.attachSession()

        assertEquals(2, activity.sessionId?.value)
        val sessions = (core.api.sessions() as AppShellResult.Success).value
        assertEquals(2, sessions.size) // restored id 1 + exactly one new
    }

    @Test
    fun destroy_cancels_pending_attach_retry_without_creating() {
        val storeFile = java.io.File.createTempFile("restoring", ".bin").apply { delete() }
        SessionStateStore(storeFile).save(listOf(persisted(id = 1)))
        val core = TermuxServiceCore(
            SessionRegistry(engineFactory = { _, _ -> FakeEngine() }),
            stateStore = SessionStateStore(storeFile),
        )
        val activity = Robolectric.buildActivity(TerminalActivity::class.java).get()
        activity.api = core.api

        activity.attachSession() // schedules retry (restore not done)
        assertNull(activity.sessionId)
        activity.attachCancelled = true

        // Restore completes afterwards: the cancelled retry must not fire,
        // and nothing is ever created for the cancelled attempt.
        core.restoreSessions({ apply -> apply() })
        core.awaitRestoreForTest()
        shadowOf(android.os.Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)

        assertNull(activity.sessionId)
        assertEquals(1, (core.api.sessions() as AppShellResult.Success).value.size)
    }

    @Test
    fun after_restore_hook_failure_still_opens_barrier_and_starts_nothing_dead() {
        val storeFile = java.io.File.createTempFile("restoring", ".bin").apply { delete() }
        val core = TermuxServiceCore(
            SessionRegistry(engineFactory = { _, _ -> FakeEngine() }),
            stateStore = SessionStateStore(storeFile),
        )

        core.restoreSessions({ apply -> apply() }) {
            throw IllegalStateException("injected afterRestore failure")
        }

        // Barrier opened despite the hook failure; createSession proceeds.
        core.awaitRestoreForTest()
        val created = core.api.createSession(terminalRequest())
        assertIs<AppShellResult.Success<SessionId>>(created)
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

    private fun terminalRequest() = AppExecutionRequest(
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
