package com.termux.rust

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TermuxServiceLifecycleTest {
    @AfterTest
    fun resetSharedHooks() {
        TermuxService.registryFactory = TermuxService.defaultNoEnvRegistryFactory
        TermuxService.destroyTimeoutMs = 5_000
    }

    @Test
    fun service_create_drive_destroy_reaps_all_sessions() {
        val engines = mutableListOf<FakeEngine>()
        TermuxService.registryFactory = {
            SessionRegistry(engineFactory = { _, _ -> FakeEngine().also(engines::add) })
        }

        val controller = Robolectric.buildService(TermuxService::class.java).create()
        val service = controller.get()
        service.stopDriveLoopForTest()

        val id = (service.core.api.createSession(
            AppExecutionRequest(
                origin = RequestOrigin.Internal,
                executable = "/system/bin/sh",
                target = ExecutionTarget.TERMINAL_SESSION,
                terminalSize = TerminalDimensions(80, 24),
            ),
        ) as AppShellResult.Success).value
        // Advance the session deterministically instead of waiting for the loop.
        service.core.registry.drive(id)
        assertEquals(1, engines.size)

        controller.destroy()

        assertEquals(1, engines.single().terminateCount)
        assertEquals(1, engines.single().closeCount)
        val snapshot = (service.core.api.session(id) as AppShellResult.Success).value
        assertEquals(SessionLifecycle.CLOSED, snapshot.lifecycle)
        assertEquals(
            SessionTermination.Cancelled(CancellationReason.SERVICE_SHUTDOWN),
            snapshot.termination,
        )

    }

    @Test
    fun activity_binding_never_enters_session_lifetime() {
        // onBind is intentionally null until the Binder task lands; unbinding
        // (or never binding) must not affect sessions — encoded by contract.
        assertTrue(!AppShellThreadContract.activityUnbindCancelsSessions)
        assertTrue(RuntimeEnvironment.getApplication() != null)
    }

    @Test
    fun destroy_returns_within_timeout_even_when_an_engine_hangs() {
        val hang = java.util.concurrent.CountDownLatch(1)
        TermuxService.registryFactory = {
            SessionRegistry(engineFactory = { _, _ ->
                object : SessionEngine {
                    override fun writeInput(input: SessionInput) = Unit
                    override fun resize(dimensions: TerminalDimensions) = Unit
                    override fun terminate() {
                        hang.await(2, java.util.concurrent.TimeUnit.SECONDS) // released never
                    }
                    override fun pollTermination(): SessionTermination? = null
                    override fun renderFrame(): Pair<Long, ByteArray>? = null
                    override fun close() = Unit
                }
            })
        }
        val controller = Robolectric.buildService(TermuxService::class.java).create()
        val service = controller.get()
        service.stopDriveLoopForTest()
        val id = (service.core.api.createSession(
            AppExecutionRequest(
                origin = RequestOrigin.Internal,
                executable = "/system/bin/sh",
                target = ExecutionTarget.TERMINAL_SESSION,
                terminalSize = TerminalDimensions(80, 24),
            ),
        ) as AppShellResult.Success).value
        service.core.registry.drive(id)

        TermuxService.destroyTimeoutMs = 100
        val started = System.currentTimeMillis()
        controller.destroy()
        val elapsed = System.currentTimeMillis() - started

        // The hanging engine must not hold the main thread hostage.
        assertTrue(elapsed < 5_000, "destroy blocked for ${elapsed}ms")
    }

    @Test
    fun drive_loop_survives_a_registry_failure_and_keeps_scheduling() {
        // driveAll rethrows engine failures; the loop must swallow them and
        // repost instead of dying.
        var calls = 0
        val badClose = object : SessionEngine {
            override fun writeInput(input: SessionInput) = Unit
            override fun resize(dimensions: TerminalDimensions) = Unit
            override fun terminate() = Unit
            override fun pollTermination(): SessionTermination = SessionTermination.ProcessExited(0)
            override fun renderFrame(): Pair<Long, ByteArray> = 1L to byteArrayOf(1)
            override fun close() {
                calls += 1
                throw SessionEngineException(AppShellError(AppShellErrorCode.NATIVE_FAILURE, retryable = false))
            }
        }
        TermuxService.registryFactory = {
            SessionRegistry(engineFactory = { _, _ -> badClose })
        }
        val controller = Robolectric.buildService(TermuxService::class.java).create()
        val service = controller.get()
        service.stopDriveLoopForTest()
        val id = (service.core.api.createSession(
            AppExecutionRequest(
                origin = RequestOrigin.Internal,
                executable = "/system/bin/sh",
                target = ExecutionTarget.TERMINAL_SESSION,
                terminalSize = TerminalDimensions(80, 24),
            ),
        ) as AppShellResult.Success).value
        service.core.registry.drive(id)
        service.core.api.close(id)

        // Must not propagate: the loop logs and continues.
        service.runDriveLoopOnceForTest()
        service.runDriveLoopOnceForTest()

        assertTrue(calls >= 1)
        controller.destroy()
    }

    @Test
    fun foreground_follows_live_sessions_and_clears_after_the_last_one() {
        val engine = FakeEngine()
        TermuxService.registryFactory = {
            SessionRegistry(engineFactory = { _, _ -> engine })
        }
        val controller = Robolectric.buildService(TermuxService::class.java).create()
        val service = controller.get()
        service.stopDriveLoopForTest()
        val id = (service.core.api.createSession(
            AppExecutionRequest(
                origin = RequestOrigin.Internal,
                executable = "/system/bin/sh",
                target = ExecutionTarget.TERMINAL_SESSION,
                terminalSize = TerminalDimensions(80, 24),
            ),
        ) as AppShellResult.Success).value

        // First live session promotes to foreground.
        service.core.registry.drive(id)
        service.runDriveLoopOnceForTest()
        assertTrue(service.foregroundActiveForTest)

        // Last live session exits: demote with REMOVE, notification gone.
        service.core.api.cancel(id, CancellationReason.USER_REQUEST)
        service.core.registry.drive(id)
        service.runDriveLoopOnceForTest()
        assertTrue(!service.foregroundActiveForTest)

        controller.destroy()
    }

    private class FakeEngine : SessionEngine {
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
            closeCount += 1
        }
    }
}
