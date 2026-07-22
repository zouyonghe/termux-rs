package com.termux.rust

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
            assertEquals(0, session.pollExitCode())
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

    @Test
    fun render_retries_with_fresh_size_when_snapshot_grows_between_calls() {
        var size = 4
        var attempts = 0
        val rendered = renderSnapshotBytes(
            querySize = { size },
            copyInto = { buffer ->
                attempts += 1
                if (attempts == 1) {
                    size = 6 // snapshot grew between the size query and the copy
                    0 // native side reports a short/zeroed write
                } else {
                    buffer.fill(1)
                    buffer.size
                }
            },
        )

        assertEquals(2, attempts)
        assertEquals(6, rendered.size)
        assertTrue(rendered.all { it == 1.toByte() })
    }

    @Test
    fun render_returns_empty_when_native_side_never_completes_a_snapshot() {
        var attempts = 0
        val rendered = renderSnapshotBytes(
            querySize = { 4 },
            copyInto = { attempts += 1; 0 },
        )

        assertEquals(3, attempts)
        assertEquals(0, rendered.size)

        val empty = renderSnapshotBytes(querySize = { 0 }, copyInto = { 0 })
        assertEquals(0, empty.size)
    }

    @Test
    fun poll_exit_code_is_null_while_child_runs() {
        val bridge = FakeBridge(tryWaitStatus = NativeStatus.SESSION_RUNNING)
        TerminalSessionController("sh", emptyList(), 80, 24, bridge).use { session ->
            assertEquals(null, session.pollExitCode())
        }
    }

    private class FakeBridge(
        private val status: Int = 0,
        private val tryWaitStatus: Int = 0,
    ) : NativeTerminalSessionBridge {
        var freeCount = 0
        override fun create(command: String, arguments: List<String>, columns: Int, rows: Int) = 1L
        override fun feedOutput(handle: Long, bytes: ByteArray) = status
        override fun writeInput(handle: Long, bytes: ByteArray) = status
        override fun pumpOutput(handle: Long) = 1
        override fun render(handle: Long) = "hi".encodeToByteArray()
        override fun resize(handle: Long, columns: Int, rows: Int) = status
        override fun tryWait(handle: Long, exitCodeOut: IntArray): Int {
            exitCodeOut[0] = 0
            return tryWaitStatus
        }
        override fun terminate(handle: Long) = status
        override fun free(handle: Long) { freeCount += 1 }
    }
}
