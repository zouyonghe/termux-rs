package com.termux.rust

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TerminalSessionSupervisorTest {
    @Test
    fun surfaces_child_exit_code_and_keeps_final_frame_renderable() {
        val bridge = FakeBridge()
        val supervisor = TerminalSessionSupervisor("sh", emptyList(), 80, 24, bridge = bridge)

        assertContentEquals("hi".encodeToByteArray(), supervisor.pumpFrame())
        assertNull(supervisor.exitCode)

        bridge.pumpStatus = NativeStatus.SESSION_OUTPUT_CLOSED
        bridge.tryWaitStatus = NativeStatus.OK
        bridge.exitCode = 3

        // OUTPUT_CLOSED is a terminal lifecycle event: exit code surfaces,
        // and the final frame still renders so last output stays visible.
        assertContentEquals("hi".encodeToByteArray(), supervisor.pumpFrame())
        assertEquals(3, supervisor.exitCode)
        supervisor.close()
    }

    @Test
    fun ignores_input_and_resize_after_exit_or_close() {
        val bridge = FakeBridge()
        bridge.tryWaitStatus = NativeStatus.OK
        bridge.exitCode = 0
        val supervisor = TerminalSessionSupervisor("sh", emptyList(), 80, 24, bridge = bridge)

        supervisor.pumpFrame() // picks up the exit code
        supervisor.writeInput("x".encodeToByteArray())
        supervisor.resize(10, 10)
        assertEquals(0, bridge.writeInputCount)
        assertEquals(0, bridge.resizeCount)

        supervisor.close()
        supervisor.writeInput("x".encodeToByteArray())
        assertEquals(0, bridge.writeInputCount)
    }

    @Test
    fun terminate_and_close_are_idempotent() {
        val bridge = FakeBridge()
        bridge.tryWaitStatus = NativeStatus.OK // child reaped immediately after terminate
        val supervisor = TerminalSessionSupervisor("sh", emptyList(), 80, 24, bridge = bridge)

        supervisor.terminate()
        supervisor.terminate()
        assertEquals(1, bridge.terminateCount)
        assertEquals(0, supervisor.exitCode)

        supervisor.close()
        supervisor.close()
        assertEquals(1, bridge.terminateCount) // no second terminate after exit
        assertEquals(1, bridge.freeCount)
    }

    @Test
    fun close_terminates_a_running_child_exactly_once() {
        val bridge = FakeBridge()
        val supervisor = TerminalSessionSupervisor("sh", emptyList(), 80, 24, bridge = bridge)

        supervisor.close()
        supervisor.close()
        assertEquals(1, bridge.terminateCount)
        assertEquals(1, bridge.freeCount)
        assertNull(supervisor.pumpFrame())
    }

    @Test
    fun propagates_unexpected_pump_status() {
        val bridge = FakeBridge()
        bridge.pumpStatus = -3
        val supervisor = TerminalSessionSupervisor("sh", emptyList(), 80, 24, bridge = bridge)

        assertFailsWith<IllegalStateException> { supervisor.pumpFrame() }
        supervisor.close()
    }

    private class FakeBridge : NativeTerminalSessionBridge {
        var pumpStatus = NativeStatus.SESSION_RUNNING
        var tryWaitStatus = NativeStatus.SESSION_RUNNING
        var exitCode = 0
        var writeInputCount = 0
        var resizeCount = 0
        var terminateCount = 0
        var freeCount = 0

        override fun create(command: String, arguments: List<String>, columns: Int, rows: Int) = 1L
        override fun feedOutput(handle: Long, bytes: ByteArray) = NativeStatus.OK
        override fun writeInput(handle: Long, bytes: ByteArray): Int {
            writeInputCount += 1
            return NativeStatus.OK
        }
        override fun pumpOutput(handle: Long) = pumpStatus
        override fun render(handle: Long) = "hi".encodeToByteArray()
        override fun resize(handle: Long, columns: Int, rows: Int): Int {
            resizeCount += 1
            return NativeStatus.OK
        }
        override fun tryWait(handle: Long, exitCodeOut: IntArray): Int {
            exitCodeOut[0] = exitCode
            return tryWaitStatus
        }
        override fun terminate(handle: Long): Int {
            terminateCount += 1
            return NativeStatus.OK
        }
        override fun free(handle: Long) { freeCount += 1 }
    }
}
