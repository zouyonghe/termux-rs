package com.termux.rust

import android.content.Intent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RunCommandServiceTest {
    @AfterTest
    fun reset() {
        PendingRunCommands.clear()
        RunCommandService.termuxServiceStarter = RunCommandService.defaultTermuxServiceStarter
        TermuxService.registryFactory = {
            SessionRegistry(engineFactory = { request, id -> RustSessionEngine(request, id) })
        }
    }

    @Test
    fun cold_start_queues_valid_request_until_termux_service_drains_it() {
        PendingRunCommands.clear()
        // Deliberately do NOT pre-create TermuxService: the validated request
        // must survive in the handoff queue, not depend on a live instance.
        val controller = Robolectric.buildService(RunCommandService::class.java).create()
        controller.get().onStartCommand(validIntent(), 0, 1)

        assertEquals(1, PendingRunCommands.size)

        TermuxService.registryFactory = {
            SessionRegistry(engineFactory = { _, _ -> FakeEngine() })
        }
        Robolectric.buildService(TermuxService::class.java).create()

        // Drain is async (restore barrier -> worker load -> owner apply ->
        // drain). Poll with an upper bound instead of assuming synchrony.
        var sessions: List<SessionSnapshot> = emptyList()
        awaitTrue("run-command session drained") {
            sessions = (TermuxService.instance!!.core.api.sessions() as AppShellResult.Success).value
            sessions.isNotEmpty()
        }
        assertEquals(0, PendingRunCommands.size)
        assertEquals(1, sessions.size)
        assertEquals(ExecutionTarget.APP_SHELL, sessions.single().target)
    }

    @Test
    fun concurrent_intents_serialize_through_the_queue_exactly_once() {
        PendingRunCommands.clear()
        val controller = Robolectric.buildService(RunCommandService::class.java).create()
        val service = controller.get()

        val intents = (1..20).map { index ->
            Intent(RunCommandIntentParser.ACTION_RUN_COMMAND)
                .putExtra(RunCommandIntentParser.EXTRA_PATH, "/system/bin/true")
                .putExtra(RunCommandIntentParser.EXTRA_LABEL, "cmd-$index")
        }
        intents.parallelStream().forEach { intent ->
            service.onStartCommand(intent, 0, 0)
        }
        assertEquals(20, PendingRunCommands.size)

        TermuxService.registryFactory = {
            SessionRegistry(engineFactory = { _, _ -> FakeEngine() })
        }
        Robolectric.buildService(TermuxService::class.java).create()

        var sessions: List<SessionSnapshot> = emptyList()
        val svc = TermuxService.instance!!
        awaitTrue("all 20 drained") {
            sessions = (svc.core.api.sessions() as AppShellResult.Success).value
            sessions.size == 20
        }
        assertEquals(20, sessions.size)
        assertEquals(0, PendingRunCommands.size)
    }

    @Test
    fun malformed_or_wrong_action_never_enters_the_queue() {
        PendingRunCommands.clear()
        val controller = Robolectric.buildService(RunCommandService::class.java).create()

        controller.get().onStartCommand(Intent("android.intent.action.VIEW"), 0, 1)
        controller.get().onStartCommand(null, 0, 2)
        controller.get().onStartCommand(
            Intent(RunCommandIntentParser.ACTION_RUN_COMMAND).putExtra(RunCommandIntentParser.EXTRA_PATH, "relative/sh"),
            0,
            3,
        )

        assertEquals(0, PendingRunCommands.size)
    }

    @Test
    fun rollback_removes_the_failed_token_not_an_identical_twin() {
        PendingRunCommands.clear()
        // Two requests with byte-identical content: content-based removal
        // would roll back the WRONG one.
        val first = PendingRunCommands.enqueue(request())
        val second = PendingRunCommands.enqueue(request())

        PendingRunCommands.remove(second) // rollback of the second only

        val remaining = PendingRunCommands.pendingTokensForTest()
        assertEquals(listOf(first), remaining)
        val drained = PendingRunCommands.drain()
        assertEquals(1, drained.size)
        assertTrue(PendingRunCommands.pendingTokensForTest().isEmpty())
    }

    @Test
    fun failed_start_rolls_back_its_own_request_and_never_executes_late() {
        PendingRunCommands.clear()
        TermuxService.registryFactory = {
            SessionRegistry(engineFactory = { _, _ -> FakeEngine() })
        }
        val controller = Robolectric.buildService(RunCommandService::class.java).create()
        val service = controller.get()

        // First identical request: start succeeds.
        service.onStartCommand(validIntent(), 0, 1)
        // Second, content-identical request: start fails -> rollback.
        RunCommandService.termuxServiceStarter = { throw IllegalStateException("background start blocked") }
        service.onStartCommand(validIntent(), 0, 2)

        // Exactly one request remains — and it must be the first token.
        assertEquals(1, PendingRunCommands.size)
        Robolectric.buildService(TermuxService::class.java).create()
        var sessions: List<SessionSnapshot> = emptyList()
        awaitTrue("run-command session drained") {
            sessions = (TermuxService.instance!!.core.api.sessions() as AppShellResult.Success).value
            sessions.isNotEmpty()
        }
        assertEquals(1, sessions.size)

        // The failed request must never fire later: queue stays empty across
        // further drive passes.
        TermuxService.instance!!.stopDriveLoopForTest()
        TermuxService.instance!!.runDriveLoopOnceForTest()
        TermuxService.instance!!.runDriveLoopOnceForTest()
        assertEquals(0, PendingRunCommands.size)
        assertEquals(1, sessions.size)
    }

    private fun request() = AppExecutionRequest(
        origin = RequestOrigin.ExternalRunCommand(caller = null),
        executable = "/system/bin/true",
        target = ExecutionTarget.APP_SHELL,
    )

    private fun awaitTrue(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(15)
        }
        throw AssertionError("timed out: " + description)
    }

    private fun validIntent(): Intent = Intent(RunCommandIntentParser.ACTION_RUN_COMMAND)
        .putExtra(RunCommandIntentParser.EXTRA_PATH, "/system/bin/true")

    private class FakeEngine : SessionEngine {
        override fun writeInput(input: SessionInput) = Unit
        override fun resize(dimensions: TerminalDimensions) = Unit
        override fun terminate() = Unit
        override fun pollTermination(): SessionTermination? = null
        override fun renderFrame(): Pair<Long, ByteArray>? = null
        override fun close() = Unit
    }
}
