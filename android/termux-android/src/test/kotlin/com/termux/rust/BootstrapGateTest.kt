package com.termux.rust

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BootstrapGateTest {
    @Test
    fun prefix_executable_requires_ready_bootstrap() {
        val root = tempRoot()
        val core = TermuxServiceCore(
            SessionRegistry(engineFactory = { _, _ -> FakeEngine() }),
            bootstrap = BootstrapInstaller(root, MissingPayloadSource),
        )
        val executable = File(root, "usr/bin/bash").absolutePath

        val result = core.api.createSession(request(executable))

        assertIs<AppShellResult.Failure>(result)
        assertEquals(AppShellErrorCode.BOOTSTRAP_UNAVAILABLE, result.error.code)
        assertTrue(result.error.retryable)
        // Nothing reached the registry.
        assertTrue((core.registry.sessions() as AppShellResult.Success).value.isEmpty())
    }

    @Test
    fun absolute_system_programs_are_never_rewritten_by_bootstrap_state() {
        val root = tempRoot()
        val core = TermuxServiceCore(
            SessionRegistry(engineFactory = { _, _ -> FakeEngine() }),
            bootstrap = BootstrapInstaller(root, MissingPayloadSource),
        )

        val result = core.api.createSession(request("/system/bin/sh"))

        assertIs<AppShellResult.Success<SessionId>>(result)
    }

    @Test
    fun prefix_executable_passes_once_ready() {
        val root = tempRoot()
        val installer = BootstrapInstaller(root, ZipPayloadSource(goodZip()))
        installer.installIfNeeded()
        val core = TermuxServiceCore(
            SessionRegistry(engineFactory = { _, _ -> FakeEngine() }),
            bootstrap = installer,
        )
        val executable = File(root, "usr/bin/tool").absolutePath

        val result = core.api.createSession(request(executable))

        assertIs<AppShellResult.Success<SessionId>>(result)
    }

    @Test
    fun asset_sidecar_metadata_is_strictly_validated() {
        assertEquals("1.0", AssetBootstrapPayloadSource.validateVersion(" 1.0\n"))
        assertFailsWith<BootstrapInstallException> {
            AssetBootstrapPayloadSource.validateVersion(null)
        }
        assertFailsWith<BootstrapInstallException> {
            AssetBootstrapPayloadSource.validateVersion("   ")
        }

        val sha = "a".repeat(64)
        assertEquals(sha, AssetBootstrapPayloadSource.validateSha256(" $sha\n"))
        assertFailsWith<BootstrapInstallException> {
            AssetBootstrapPayloadSource.validateSha256(null)
        }
        assertFailsWith<BootstrapInstallException> {
            AssetBootstrapPayloadSource.validateSha256("not-hex")
        }
        assertFailsWith<BootstrapInstallException> {
            AssetBootstrapPayloadSource.validateSha256("a".repeat(63))
        }
    }

    private fun request(executable: String) = AppExecutionRequest(
        origin = RequestOrigin.Internal,
        executable = executable,
        target = ExecutionTarget.TERMINAL_SESSION,
        terminalSize = TerminalDimensions(80, 24),
    )

    private fun tempRoot(): File =
        File.createTempFile("bootstrap-gate", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
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
