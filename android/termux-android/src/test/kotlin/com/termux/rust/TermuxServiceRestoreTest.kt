package com.termux.rust

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Crash-recovery integration: an abrupt "process death" (no destroy) followed
 * by a fresh service must restore only safe metadata — closed sessions keep
 * their termination, live-at-crash sessions become observable terminal
 * failures and are never respawned, and repeated starts never duplicate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TermuxServiceRestoreTest {
    private lateinit var storeFile: File

    @AfterTest
    fun reset() {
        TermuxService.registryFactory = TermuxService.defaultNoEnvRegistryFactory
        TermuxService.stateStoreFactory = { context ->
            SessionStateStore(File(context.filesDir, "session-state.bin"))
        }
        if (::storeFile.isInitialized) storeFile.delete()
    }

    @Test
    fun abrupt_death_restores_metadata_and_fails_live_sessions_observably() {
        storeFile = File.createTempFile("session-state", ".bin").apply { delete() }
        val engines = mutableListOf<FakeEngine>()
        TermuxService.stateStoreFactory = { SessionStateStore(storeFile) }
        TermuxService.registryFactory = {
            SessionRegistry(engineFactory = { _, _ -> FakeEngine().also { engines += it } })
        }

        // First "process": one exited session, one live session.
        val first = Robolectric.buildService(TermuxService::class.java).create().get()
        first.stopDriveLoopForTest()
        val exitedId = (first.core.api.createSession(request()) as AppShellResult.Success).value
        val liveId = (first.core.api.createSession(request()) as AppShellResult.Success).value
        first.core.registry.drive(exitedId)
        first.core.registry.drive(liveId)
        engines[0].pendingTermination = SessionTermination.ProcessExited(7)
        first.core.registry.drive(exitedId)
        // Persist through the real loop path.
        first.runDriveLoopOnceForTest()

        // Abrupt death: no destroy, no cleanup — straight to a new instance.
        val second = Robolectric.buildService(TermuxService::class.java).create().get()
        second.stopDriveLoopForTest()
        val restored = java.util.concurrent.CountDownLatch(1)
        second.core.restoreSessions({ apply ->
            second.runOnOwnerThreadForTest {
                apply()
                restored.countDown()
            }
        })
        assertTrue(restored.await(5, java.util.concurrent.TimeUnit.SECONDS))

        // Exited session: metadata restored, termination retained.
        val restoredExited = (second.core.api.session(exitedId) as AppShellResult.Success).value
        assertEquals(SessionLifecycle.CLOSED, restoredExited.lifecycle)
        assertEquals(SessionTermination.ProcessExited(7), restoredExited.termination)

        // Live-at-crash session: observable terminal failure (already closed
        // by the time the barrier opens, or about to be), never respawned.
        val restoredLive = (second.core.api.session(liveId) as AppShellResult.Success).value
        assertTrue(
            restoredLive.lifecycle == SessionLifecycle.EXITED ||
                restoredLive.lifecycle == SessionLifecycle.CLOSED,
        )
        val termination = restoredLive.termination
        assertIs<SessionTermination.Failed>(termination)
        assertEquals(AppShellErrorCode.INTERNAL_FAILURE, termination.error.code)
        val engineCountBefore = engines.size
        second.core.registry.drive(liveId)
        second.core.registry.reapTerminals()
        assertEquals(engineCountBefore, engines.size)
        assertEquals(
            SessionLifecycle.CLOSED,
            (second.core.api.session(liveId) as AppShellResult.Success).value.lifecycle,
        )

        // Fresh sessions get ids beyond the restored ones; restore does not
        // duplicate on a second call.
        val fresh = (second.core.api.createSession(request()) as AppShellResult.Success).value
        assertTrue(fresh.value > liveId.value)
        val restoredTwice = java.util.concurrent.CountDownLatch(1)
        second.core.restoreSessions({ apply ->
            second.runOnOwnerThreadForTest {
                apply()
                restoredTwice.countDown()
            }
        })
        assertTrue(restoredTwice.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(3, (second.core.api.sessions() as AppShellResult.Success).value.size)

        Robolectric.buildService(TermuxService::class.java).destroy()
    }

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
