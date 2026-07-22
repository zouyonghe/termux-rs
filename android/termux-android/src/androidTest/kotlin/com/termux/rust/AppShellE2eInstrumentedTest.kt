package com.termux.rust

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Full app-shell connected flow: fixture bootstrap install, two isolated
 * sessions (one typed-API terminal, one RunCommandService app-shell),
 * bind/rebind, content/exit-code identity assertions, explicit close,
 * exit banner on retained CLOSED metadata, and service teardown.
 *
 * The bootstrap archive is a test-only fixture from androidTest assets
 * injected through the production installer boundary hook. The repository
 * ships no production payload; the real asset path still reports MISSING.
 */
@RunWith(AndroidJUnit4::class)
class AppShellE2eInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private var installedBootstrap: BootstrapInstaller? = null
    private var serviceConnection: ServiceConnection? = null

    @After
    fun resetHooks() {
        serviceConnection?.let { runCatching { context.unbindService(it) } }
        runCatching { context.stopService(Intent(context, TermuxService::class.java)) }
        runCatching { File(context.filesDir, "usr").deleteRecursively() }
        runCatching { File(context.filesDir, ".bootstrap.lock").delete() }
        TermuxService.registryFactory = {
            SessionRegistry(engineFactory = { request, id -> RustSessionEngine(request, id) })
        }
        TermuxService.bootstrapInstallerFactory = { ctx ->
            BootstrapInstaller(
                root = ctx.filesDir,
                payload = AssetBootstrapPayloadSource(ctx.assets, TermuxService.BOOTSTRAP_ASSET),
            )
        }
        RunCommandService.termuxServiceStarter = RunCommandService.defaultTermuxServiceStarter
        PendingRunCommands.clear()
    }

    @Test
    fun app_shell_end_to_end() {
        val zipBytes = context.assets.open("e2e-bootstrap.zip").use { it.readBytes() }
        TermuxService.registryFactory = {
            SessionRegistry(engineFactory = { request, id -> RustSessionEngine(request, id) })
        }
        TermuxService.bootstrapInstallerFactory = { ctx ->
            BootstrapInstaller(ctx.filesDir, ZipPayloadSource(zipBytes)).also { installedBootstrap = it }
        }

        // 1. Verified fixture install through the production boundary.
        assertTrue(context.startService(Intent(context, TermuxService::class.java)) != null)
        awaitState("bootstrap READY") {
            installedBootstrap?.state() == BootstrapInstallState.READY
        }
        assertTrue(File(context.filesDir, "usr/bin/hello").canExecute())

        // 2. Bind: typed same-app Binder facade.
        val api = bindAndAwait()

        // 3. Session A (terminal target) with deterministic output and exit 3.
        val sessionA = (api.createSession(
            AppExecutionRequest(
                origin = RequestOrigin.Internal,
                executable = "/system/bin/sh",
                target = ExecutionTarget.TERMINAL_SESSION,
                terminalSize = TerminalDimensions(80, 24),
            ),
        ) as AppShellResult.Success).value
        api.writeInput(sessionA, SessionInput.of("echo alpha-\$((10+2)); exit 3\n".encodeToByteArray()))
        awaitState("session A exited 3") {
            (api.session(sessionA) as? AppShellResult.Success)
                ?.value?.termination == SessionTermination.ProcessExited(3)
        }

        // 4. Session B through the exported adapter: validated Intent only.
        PendingRunCommands.clear()
        // Service is already running; skip the start hop for this direct call.
        RunCommandService.termuxServiceStarter = { }
        val intent = Intent(RunCommandIntentParser.ACTION_RUN_COMMAND)
            .putExtra(RunCommandIntentParser.EXTRA_PATH, "/system/bin/sh")
            .putExtra(
                RunCommandIntentParser.EXTRA_ARGUMENTS,
                arrayOf("-c", "echo beta-\$((20+3)); exit 5"),
            )
        RunCommandService().onStartCommand(intent, 0, 1)
        // Drain is proven by the session itself appearing with the right
        // identity, output, and exit code — not by a queue-size race.
        var sessionB: SessionId? = null
        awaitState("run-command session drained and exited 5") {
            val match = (api.sessions() as? AppShellResult.Success)?.value
                ?.firstOrNull { it.target == ExecutionTarget.APP_SHELL }
            sessionB = match?.id
            match?.termination == SessionTermination.ProcessExited(5)
        }

        // 5. Identity/content: each session's frame carries only its own marker.
        val textA = awaitFrameText(api, sessionA)
        assertTrue("session A frame missing its marker:\n$textA", textA.contains("alpha-12"))
        val textB = awaitFrameText(api, sessionB!!)
        assertTrue("session B frame missing its marker:\n$textB", textB.contains("beta-23"))
        assertFalse("frames crossed sessions", textA.contains("beta-23") || textB.contains("alpha-12"))

        // 6. Rebind: sessions survive; nothing is re-created.
        unbind()
        val rebound = bindAndAwait()
        val idsAfterRebind = (rebound.sessions() as AppShellResult.Success).value.map { it.id }
        assertTrue(sessionA in idsAfterRebind && sessionB in idsAfterRebind)

        // 7. Explicit close: retained with termination for the banner window.
        rebound.close(sessionA)
        awaitState("session A closed with retained termination") {
            val snapshot = (rebound.session(sessionA) as? AppShellResult.Success)?.value
            snapshot?.lifecycle == SessionLifecycle.CLOSED &&
                snapshot.termination == SessionTermination.ProcessExited(3)
        }

        // 8. Activity: pre-bind input executes exactly once; banner survives
        //    the EXITED->CLOSED transition of the retention pass.
        ActivityScenario.launch(TerminalActivity::class.java).use { scenario ->
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.sendStringSync("echo prebind-\$((30+4))\n")
            awaitActivityText(scenario, "prebind-34")
            scenario.onActivity { activity ->
                val text = activityText(activity)
                assertEquals(
                    "pre-bind input must execute exactly once:\n$text",
                    1,
                    Regex("prebind-34").findAll(text).count(),
                )
            }

            instrumentation.sendStringSync("exit 7\n")
            awaitActivityText(scenario, "process exited with code 7")
            // Retention closes the engine quickly; the banner must survive on
            // retained CLOSED metadata. Prove both sides in one bounded wait:
            // the banner is on screen AND the attached session is CLOSED
            // with its termination retained.
            awaitState("banner visible while attached session is CLOSED with exit 7 retained") {
                var bannerVisible = false
                var attachedId: SessionId? = null
                scenario.onActivity { activity ->
                    bannerVisible = activityText(activity).contains("process exited with code 7")
                    attachedId = activity.attachedSessionId?.let(::SessionId)
                }
                val snapshot = attachedId
                    ?.let { (rebound.session(it) as? AppShellResult.Success)?.value }
                bannerVisible &&
                    snapshot?.lifecycle == SessionLifecycle.CLOSED &&
                    snapshot.termination == SessionTermination.ProcessExited(7)
            }
        }

        // 9. Teardown: destroy closes everything; no foreground residue.
        val service = TermuxService.instance
        unbind()
        assertTrue(context.stopService(Intent(context, TermuxService::class.java)))
        awaitState("service destroyed") { TermuxService.instance == null }
        val snapshots = (service!!.core.api.sessions() as AppShellResult.Success).value
        assertTrue(
            "all sessions must reach CLOSED on destroy: $snapshots",
            snapshots.all { it.lifecycle == SessionLifecycle.CLOSED },
        )
        assertFalse(service.foregroundActiveForTest)
    }

    // --- helpers -------------------------------------------------------------

    private fun bindAndAwait(): TermuxServiceApi {
        val latch = CountDownLatch(1)
        var binder: TermuxServiceApi? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                binder = service as TermuxServiceApi
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        serviceConnection = connection
        assertTrue(
            context.bindService(Intent(context, TermuxService::class.java), connection, Context.BIND_AUTO_CREATE),
        )
        assertTrue("bind timed out", latch.await(5, TimeUnit.SECONDS))
        return binder!!
    }

    private fun unbind() {
        serviceConnection?.let { context.unbindService(it) }
        serviceConnection = null
    }

    private fun awaitFrameText(api: TermuxServiceApi, id: SessionId): String {
        var text = ""
        awaitState("frame for $id") {
            val frame = (api.cachedFrame(id) as? AppShellResult.Success)?.value
            if (frame != null) {
                text = TerminalSnapshotCodec.decode(frame.payload).cells
                    .joinToString("\n") { row -> row.joinToString("") { it.text } }
                text.isNotBlank()
            } else {
                false
            }
        }
        return text
    }

    private fun activityText(activity: TerminalActivity): String {
        val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
        return (content.getChildAt(0) as android.widget.TextView).text.toString()
    }

    private fun awaitActivityText(scenario: ActivityScenario<TerminalActivity>, marker: String) {
        var lastText = ""
        try {
            awaitState("activity shows '$marker'") {
                scenario.onActivity { activity -> lastText = activityText(activity) }
                lastText.contains(marker)
            }
        } catch (error: AssertionError) {
            throw AssertionError("${error.message}\nlast activity text:\n$lastText")
        }
    }

    private fun awaitState(description: String, timeoutMs: Long = 12_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        val registry = TermuxService.instance?.core?.registry
        val sessions = registry?.let { (it.sessions() as? AppShellResult.Success)?.value }
        val summary = registry?.summary()
        val foreground = TermuxService.instance?.foregroundActiveForTest
        fail(
            "timed out waiting for: $description\n" +
                "registry summary: $summary\nsessions: $sessions\n" +
                "foregroundActive: $foreground\npendingQueue: ${PendingRunCommands.size}",
        )
    }
}
