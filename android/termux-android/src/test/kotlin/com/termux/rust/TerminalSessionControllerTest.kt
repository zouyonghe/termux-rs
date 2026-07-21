package com.termux.rust

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TerminalSessionControllerTest {
    @Test
    fun owns_session_lifecycle_and_delegates_terminal_operations() {
        val bridge = FakeBridge()
        TerminalSessionController("sh", listOf("-c", "true"), 80, 24, bridge).use { session ->
            session.feedOutput("hi".encodeToByteArray())
            session.writeInput("x".encodeToByteArray())
            session.resize(100, 40)
            assertContentEquals("hi".encodeToByteArray(), session.render())
            assertEquals(1, session.pumpOutput())
            assertEquals(0, session.tryWait())
            session.terminate()
        }
        assertEquals(1, bridge.freeCount)
    }

    @Test
    fun rejects_closed_and_failed_native_sessions() {
        val bridge = FakeBridge()
        val session = TerminalSessionController("sh", emptyList(), 80, 24, bridge)
        session.close()
        session.close()
        assertEquals(1, bridge.freeCount)
        assertFailsWith<IllegalStateException> { session.render() }

        val failing = FakeBridge(status = -2)
        TerminalSessionController("sh", emptyList(), 80, 24, failing).use { failingSession ->
            assertFailsWith<IllegalStateException> { failingSession.resize(1, 1) }
        }
    }

    private class FakeBridge(private val status: Int = 0) : NativeTerminalSessionBridge {
        var freeCount = 0
        override fun create(command: String, arguments: List<String>, columns: Int, rows: Int) = 1L
        override fun feedOutput(handle: Long, bytes: ByteArray) = status
        override fun writeInput(handle: Long, bytes: ByteArray) = status
        override fun pumpOutput(handle: Long) = 1
        override fun render(handle: Long) = "hi".encodeToByteArray()
        override fun resize(handle: Long, columns: Int, rows: Int) = status
        override fun tryWait(handle: Long) = 0
        override fun terminate(handle: Long) = status
        override fun free(handle: Long) { freeCount += 1 }
    }
}
