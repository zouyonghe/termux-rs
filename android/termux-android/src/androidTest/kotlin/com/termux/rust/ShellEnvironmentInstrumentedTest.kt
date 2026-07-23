package com.termux.rust

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies required environment values in real interactive and app-shell children. */
@RunWith(AndroidJUnit4::class)
class ShellEnvironmentInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun stopServiceAndClearState() {
        runCatching { context.stopService(Intent(context, TermuxService::class.java)) }
        runCatching { File(context.filesDir, "session-state.bin").delete() }
    }

    @Test
    fun native_bridge_exposes_runtime_environment_and_creates_session() {
        File(context.filesDir, "home").mkdirs()
        File(context.filesDir, "usr/tmp").mkdirs()
        val required = JniTerminalSessionBridge.runtimeEnvironment(context.packageName)
        assertTrue("runtime environment was empty", required.isNotEmpty())
        val environment = System.getenv().map { "${it.key}=${it.value}" } + required

        val handle = JniTerminalSessionBridge.createWithEnv(
            "/system/bin/sh",
            listOf("-c", "exit 0"),
            environment,
            80,
            24,
        )
        assertTrue("native createWithEnv returned null", handle != 0L)
        if (handle != 0L) JniTerminalSessionBridge.free(handle)
    }

    @Test
    fun interactive_shell_receives_required_environment() {
        ActivityScenario.launch(TerminalActivity::class.java).use { scenario ->
            val (service, _) = awaitAttachedSession(scenario)
            val id = (service.createSession(
                AppExecutionRequest(
                    origin = RequestOrigin.Internal,
                    executable = "/system/bin/sh",
                    arguments = listOf("-c", "echo env=\$PREFIX:\$TERM:\$LANG:\$HOME"),
                    target = ExecutionTarget.TERMINAL_SESSION,
                    terminalSize = TerminalDimensions(80, 24),
                ),
            ) as AppShellResult.Success).value

            val files = "/data/data/${context.packageName}/files"
            awaitFrameText(service, id) { text ->
                text.replace("\n", "")
                    .contains("env=$files/usr:xterm-256color:C.UTF-8:$files/home")
            }
        }
    }

    @Test
    fun background_run_command_receives_required_environment_and_metadata() {
        ActivityScenario.launch(TerminalActivity::class.java).use { scenario ->
            val (service, _) = awaitAttachedSession(scenario)
            val id = (service.createSession(
                AppExecutionRequest(
                    origin = RequestOrigin.Internal,
                    executable = "/system/bin/sh",
                    arguments = listOf(
                        "-c",
                        "echo meta=\$TERMUX_APP_PACKAGE_VARIANT:\$EDITOR:\$TMPDIR",
                    ),
                    target = ExecutionTarget.APP_SHELL,
                    environment = mapOf("EDITOR" to "vi"),
                ),
            ) as AppShellResult.Success).value

            val expectedTmp = "/data/data/${context.packageName}/files/usr/tmp"
            awaitFrameText(service, id) { text ->
                text.contains("meta=apt-android-7:vi:$expectedTmp")
            }
        }
    }

    private fun awaitAttachedSession(
        scenario: ActivityScenario<TerminalActivity>,
    ): Pair<TermuxServiceApi, SessionId> {
        var result: Pair<TermuxServiceApi, SessionId>? = null
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            scenario.onActivity { activity ->
                val service = activity.api
                val id = activity.sessionId
                if (service != null && id != null) result = service to id
            }
            result?.let { return it }
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("activity never attached to TermuxService")
    }

    private fun awaitFrameText(
        service: TermuxServiceApi,
        id: SessionId,
        condition: (String) -> Boolean,
    ) {
        var lastText = ""
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val frame = (service.cachedFrame(id) as? AppShellResult.Success)?.value
            if (frame != null) {
                lastText = TerminalTextRenderer.render(TerminalSnapshotCodec.decode(frame.payload)).toString()
                if (condition(lastText)) return
            }
            Thread.sleep(POLL_MS)
        }
        val snapshot = (service.session(id) as? AppShellResult.Success)?.value
        throw AssertionError("expected frame text never rendered; session=$snapshot; last text:\n$lastText")
    }

    private companion object {
        const val TIMEOUT_MS = 12_000L
        const val POLL_MS = 25L
    }
}
