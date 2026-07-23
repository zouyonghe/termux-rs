package com.termux.rust

import android.content.Intent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RunCommandIntentParserTest {
    @Test
    fun valid_intent_becomes_immutable_typed_request() {
        val parsed = RunCommandIntentParser.parse(validIntent())

        assertIs<AppShellResult.Success<AppExecutionRequest>>(parsed)
        val request = parsed.value
        assertEquals("/system/bin/sh", request.executable)
        assertEquals(listOf("-c", "echo hi"), request.arguments)
        assertEquals("/data/data/com.termux/files/home", request.workingDirectory)
        assertEquals(mapOf("EDITOR" to "vi"), request.environment)
        assertEquals(ExecutionTimeout.After(5_000), request.timeout)
        assertEquals(ExecutionTarget.APP_SHELL, request.target)
        assertEquals(SessionExecutionMode.NONINTERACTIVE_APP_SHELL, request.sessionMode)
        // Started-service protocol: no caller attribution is fabricated.
        assertIs<RequestOrigin.ExternalRunCommand>(request.origin)
        assertNull(request.origin.caller)
    }

    @Test
    fun rejects_missing_or_wrong_action() {
        assertIs<AppShellResult.Failure>(RunCommandIntentParser.parse(null))
        assertIs<AppShellResult.Failure>(RunCommandIntentParser.parse(Intent("android.intent.action.VIEW")))
    }

    @Test
    fun rejects_missing_relative_or_nul_path() {
        assertIs<AppShellResult.Failure>(RunCommandIntentParser.parse(validIntent().removeExtra_(RunCommandIntentParser.EXTRA_PATH)))
        assertIs<AppShellResult.Failure>(RunCommandIntentParser.parse(validIntent().putExtra(RunCommandIntentParser.EXTRA_PATH, "sh")))
        assertIs<AppShellResult.Failure>(RunCommandIntentParser.parse(validIntent().putExtra(RunCommandIntentParser.EXTRA_PATH, "/system/bin/sh\u0000x")))
    }

    @Test
    fun rejects_malformed_extras() {
        // bad env entry (no '=')
        assertIs<AppShellResult.Failure>(
            RunCommandIntentParser.parse(validIntent().putExtra(RunCommandIntentParser.EXTRA_ENVIRONMENT, arrayOf("LANG"))),
        )
        // protected env override rejected by contract
        assertIs<AppShellResult.Failure>(
            RunCommandIntentParser.parse(validIntent().putExtra(RunCommandIntentParser.EXTRA_ENVIRONMENT, arrayOf("PATH=/evil"))),
        )
        // relative working directory
        assertIs<AppShellResult.Failure>(
            RunCommandIntentParser.parse(validIntent().putExtra(RunCommandIntentParser.EXTRA_WORKING_DIRECTORY, "tmp")),
        )
        // negative timeout
        assertIs<AppShellResult.Failure>(
            RunCommandIntentParser.parse(validIntent().putExtra(RunCommandIntentParser.EXTRA_TIMEOUT_MS, -1)),
        )
    }

    @Test
    fun optional_extras_default_safely() {
        val intent = Intent(RunCommandIntentParser.ACTION_RUN_COMMAND)
            .putExtra(RunCommandIntentParser.EXTRA_PATH, "/system/bin/true")
        val parsed = RunCommandIntentParser.parse(intent)

        assertIs<AppShellResult.Success<AppExecutionRequest>>(parsed)
        assertEquals(emptyList(), parsed.value.arguments)
        assertNull(parsed.value.workingDirectory)
        assertEquals(emptyMap(), parsed.value.environment)
        assertEquals(ExecutionTimeout.Unlimited, parsed.value.timeout)
    }

    @Test
    fun upstream_termux_schema_maps_to_the_same_immutable_request() {
        // Constants verified against termux-app master TermuxConstants v0.53.0.
        val intent = Intent("com.termux.RUN_COMMAND")
            .putExtra("com.termux.RUN_COMMAND_PATH", "/system/bin/sh")
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", "id"))
            .putExtra("com.termux.RUN_COMMAND_STDIN", "input\n")
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            .putExtra("com.termux.RUN_COMMAND_RUNNER", "app-shell")
            .putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "upstream")

        val parsed = RunCommandIntentParser.parse(intent)

        assertIs<AppShellResult.Success<AppExecutionRequest>>(parsed)
        val request = parsed.value
        assertEquals("/system/bin/sh", request.executable)
        assertEquals(listOf("-c", "id"), request.arguments)
        assertTrue(request.stdin!!.contentEquals("input\n".encodeToByteArray()))
        assertEquals("/data/data/com.termux/files/home", request.workingDirectory)
        assertEquals("upstream", request.label)
        assertEquals(ExecutionTarget.APP_SHELL, request.target)
        assertIs<RequestOrigin.ExternalRunCommand>(request.origin)
        assertNull(request.origin.caller)
    }

    @Test
    fun upstream_background_extra_defaults_to_app_shell_and_terminal_runner_is_rejected() {
        // No runner extra: deprecated BACKGROUND boolean decides (default true).
        val backgroundIntent = Intent("com.termux.RUN_COMMAND")
            .putExtra("com.termux.RUN_COMMAND_PATH", "/system/bin/true")
        assertIs<AppShellResult.Success<AppExecutionRequest>>(
            RunCommandIntentParser.parse(backgroundIntent),
        )

        val terminalIntent = Intent("com.termux.RUN_COMMAND")
            .putExtra("com.termux.RUN_COMMAND_PATH", "/system/bin/sh")
            .putExtra("com.termux.RUN_COMMAND_RUNNER", "terminal-session")
        assertIs<AppShellResult.Failure>(RunCommandIntentParser.parse(terminalIntent))

        val unknownRunner = Intent("com.termux.RUN_COMMAND")
            .putExtra("com.termux.RUN_COMMAND_PATH", "/system/bin/sh")
            .putExtra("com.termux.RUN_COMMAND_RUNNER", "bogus")
        assertIs<AppShellResult.Failure>(RunCommandIntentParser.parse(unknownRunner))
    }

    @Test
    fun hostile_extras_never_crash_the_parser() {
        // Wrong-typed and unparcelable payloads must yield INVALID_REQUEST.
        val wrongTypes = Intent(RunCommandIntentParser.ACTION_RUN_COMMAND)
            .putExtra(RunCommandIntentParser.EXTRA_PATH, 42)
            .putExtra(RunCommandIntentParser.EXTRA_TIMEOUT_MS, "not-a-number")
        assertIs<AppShellResult.Failure>(RunCommandIntentParser.parse(wrongTypes))
    }

    private fun validIntent(): Intent = Intent(RunCommandIntentParser.ACTION_RUN_COMMAND)
        .putExtra(RunCommandIntentParser.EXTRA_PATH, "/system/bin/sh")
        .putExtra(RunCommandIntentParser.EXTRA_ARGUMENTS, arrayOf("-c", "echo hi"))
        .putExtra(RunCommandIntentParser.EXTRA_WORKING_DIRECTORY, "/data/data/com.termux/files/home")
        .putExtra(RunCommandIntentParser.EXTRA_ENVIRONMENT, arrayOf("EDITOR=vi"))
        .putExtra(RunCommandIntentParser.EXTRA_TIMEOUT_MS, 5_000)
        .putExtra(RunCommandIntentParser.EXTRA_LABEL, "test")

    private fun Intent.removeExtra_(name: String): Intent {
        removeExtra(name)
        return this
    }
}
