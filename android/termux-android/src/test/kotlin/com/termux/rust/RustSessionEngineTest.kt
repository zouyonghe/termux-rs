package com.termux.rust

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RustSessionEngineTest {
    @Test
    fun spawn_maps_request_fields_to_native_session() {
        val bridge = FakeBridge()
        RustSessionEngine(
            request = AppExecutionRequest(
                origin = RequestOrigin.Internal,
                executable = "/system/bin/sh",
                arguments = listOf("-c", "true"),
                target = ExecutionTarget.TERMINAL_SESSION,
                terminalSize = TerminalDimensions(100, 40),
            ),
            sessionId = SessionId(1),
            bridge = bridge,
        )

        assertEquals("/system/bin/sh", bridge.createdCommand)
        assertEquals(listOf("-c", "true"), bridge.createdArguments)
        assertEquals(100, bridge.createdColumns)
        assertEquals(40, bridge.createdRows)
    }

    @Test
    fun app_shell_requests_spawn_headless_with_default_dimensions() {
        val bridge = FakeBridge()
        RustSessionEngine(
            request = AppExecutionRequest(
                origin = RequestOrigin.Internal,
                executable = "/system/bin/true",
                target = ExecutionTarget.APP_SHELL,
            ),
            sessionId = SessionId(1),
            bridge = bridge,
        )

        assertEquals(80, bridge.createdColumns)
        assertEquals(24, bridge.createdRows)
    }

    @Test
    fun forwards_input_resize_terminate_and_close() {
        val bridge = FakeBridge()
        val engine = engine(bridge)

        engine.writeInput(SessionInput.of(byteArrayOf(1, 2)))
        assertTrue(bridge.written!!.contentEquals(byteArrayOf(1, 2)))

        engine.resize(TerminalDimensions(120, 50))
        assertEquals(120 to 50, bridge.lastResize)

        engine.terminate()
        assertEquals(1, bridge.terminateCount)
        engine.close()
        assertEquals(1, bridge.freeCount)
    }

    @Test
    fun polls_exit_code_into_process_exited_termination() {
        val bridge = FakeBridge()
        val engine = engine(bridge)

        assertNull(engine.pollTermination())
        bridge.tryWaitStatus = NativeStatus.OK
        bridge.exitCode = 5
        assertEquals(SessionTermination.ProcessExited(5), engine.pollTermination())
    }

    @Test
    fun renders_frames_with_version_from_trs1_header() {
        val bridge = FakeBridge()
        val engine = engine(bridge)

        assertNull(engine.renderFrame()) // empty render: no frame yet

        bridge.rendered = trs1Frame(version = 42)
        val frame = engine.renderFrame()!!
        assertEquals(42, frame.first)
        assertTrue(frame.second.contentEquals(trs1Frame(version = 42)))
        // Every render drains PTY output into the emulator first.
        assertEquals(2, bridge.pumpCount)
    }

    @Test
    fun pump_failure_is_mapped_to_engine_exception() {
        val bridge = FakeBridge()
        bridge.pumpStatus = -3
        val engine = engine(bridge)

        val error = assertFailsWith<SessionEngineException> { engine.renderFrame() }
        assertEquals(AppShellErrorCode.NATIVE_FAILURE, error.error.code)
    }

    @Test
    fun maps_native_failures_to_engine_exceptions() {
        val bridge = FakeBridge()
        bridge.ioStatus = -3
        val engine = engine(bridge)

        val error = assertFailsWith<SessionEngineException> {
            engine.writeInput(SessionInput.of(byteArrayOf(1)))
        }
        assertEquals(AppShellErrorCode.NATIVE_FAILURE, error.error.code)

        bridge.tryWaitStatus = -2
        assertFailsWith<SessionEngineException> { engine.pollTermination() }
    }

    private fun engine(bridge: FakeBridge) = RustSessionEngine(
        request = AppExecutionRequest(
            origin = RequestOrigin.Internal,
            executable = "/system/bin/sh",
            target = ExecutionTarget.TERMINAL_SESSION,
            terminalSize = TerminalDimensions(80, 24),
        ),
        sessionId = SessionId(1),
        bridge = bridge,
    )

    private fun trs1Frame(version: Long): ByteArray =
        ByteBuffer.allocate(28 + 5).order(ByteOrder.LITTLE_ENDIAN)
            .put("TRS1".encodeToByteArray())
            .putLong(version)
            .putInt(1).putInt(1).putInt(0).putInt(0)
            .put(0).put(0).put(1).put(0).put(0)
            .array()

    private class FakeBridge : NativeTerminalSessionBridge {
        var createdCommand: String? = null
        var createdArguments: List<String>? = null
        var createdColumns = 0
        var createdRows = 0
        var written: ByteArray? = null
        var lastResize: Pair<Int, Int>? = null
        var terminateCount = 0
        var freeCount = 0
        var ioStatus = NativeStatus.OK
        var tryWaitStatus = NativeStatus.SESSION_RUNNING
        var pumpStatus = NativeStatus.SESSION_RUNNING
        var pumpCount = 0
        var exitCode = 0
        var rendered: ByteArray = ByteArray(0)

        override fun create(command: String, arguments: List<String>, columns: Int, rows: Int): Long {
            createdCommand = command
            createdArguments = arguments
            createdColumns = columns
            createdRows = rows
            return 1L
        }
        override fun feedOutput(handle: Long, bytes: ByteArray) = ioStatus
        override fun writeInput(handle: Long, bytes: ByteArray): Int {
            written = bytes
            return ioStatus
        }
        override fun pumpOutput(handle: Long): Int {
            pumpCount += 1
            return pumpStatus
        }
        override fun render(handle: Long) = rendered
        override fun resize(handle: Long, columns: Int, rows: Int): Int {
            lastResize = columns to rows
            return ioStatus
        }
        override fun tryWait(handle: Long, exitCodeOut: IntArray): Int {
            exitCodeOut[0] = exitCode
            return tryWaitStatus
        }
        override fun terminate(handle: Long): Int {
            terminateCount += 1
            return ioStatus
        }
        override fun free(handle: Long) {
            freeCount += 1
        }
    }
}
