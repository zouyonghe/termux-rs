package com.termux.rust

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pre-bind pending input/resize semantics. */
class PendingSessionOpsTest {
    @Test
    fun flush_delivers_in_fifo_order_to_the_attached_session() {
        val pending = PendingSessionOps()
        val delivered = mutableListOf<String>()
        pending.enqueue(null) { _, _ -> delivered += "first" }
        pending.enqueue(null) { _, _ -> delivered += "second" }

        pending.flush(FakeApi(), SessionId(1))

        assertEquals(listOf("first", "second"), delivered)
        assertEquals(0, pending.size)
    }

    @Test
    fun capacity_is_bounded_and_drops_oldest() {
        val pending = PendingSessionOps(capacity = 3)
        val delivered = mutableListOf<Int>()
        repeat(6) { index -> pending.enqueue(null) { _, _ -> delivered += index } }

        assertEquals(3, pending.size)
        pending.flush(FakeApi(), SessionId(1))
        assertEquals(listOf(3, 4, 5), delivered)
    }

    @Test
    fun ops_tagged_to_a_different_session_are_dropped_not_misdelivered() {
        val pending = PendingSessionOps()
        val delivered = mutableListOf<Long>()
        pending.enqueue(SessionId(1)) { _, id -> delivered += id.value }
        pending.enqueue(SessionId(2)) { _, id -> delivered += id.value }
        pending.enqueue(null) { _, id -> delivered += id.value }

        // Recreation attached session 3: session 1/2 ops must not leak into it.
        pending.flush(FakeApi(), SessionId(3))

        assertEquals(listOf(3L), delivered)
    }

    @Test
    fun clear_cancels_everything_and_flush_never_replays() {
        val pending = PendingSessionOps()
        val delivered = mutableListOf<String>()
        pending.enqueue(null) { _, _ -> delivered += "x" }
        pending.clear()
        pending.flush(FakeApi(), SessionId(1))
        assertTrue(delivered.isEmpty())

        pending.enqueue(null) { _, _ -> delivered += "y" }
        pending.flush(FakeApi(), SessionId(1))
        pending.flush(FakeApi(), SessionId(1))
        assertEquals(listOf("y"), delivered)
    }

    @Test
    fun ops_actually_reach_the_typed_api() {
        val pending = PendingSessionOps()
        val api = FakeApi()
        pending.enqueue(null) { service, id ->
            service.writeInput(id, SessionInput.of(byteArrayOf(9)))
        }

        pending.flush(api, SessionId(7))

        assertEquals(7, api.lastWriteId?.value)
        assertTrue(api.lastInput!!.copyBytes().contentEquals(byteArrayOf(9)))
    }

    private class FakeApi : TermuxServiceApi {
        var lastWriteId: SessionId? = null
        var lastInput: SessionInput? = null

        override fun createSession(request: AppExecutionRequest) = AppShellResult.Success(SessionId(1))
        override fun sessions(): AppShellResult<List<SessionSnapshot>> = AppShellResult.Success(emptyList())
        override fun session(id: SessionId): AppShellResult<SessionSnapshot> = AppShellResult.Failure(
            AppShellError(AppShellErrorCode.SESSION_NOT_FOUND, retryable = false),
        )
        override fun writeInput(id: SessionId, input: SessionInput): AppShellResult<Unit> {
            lastWriteId = id
            lastInput = input
            return AppShellResult.Success(Unit)
        }
        override fun resize(id: SessionId, dimensions: TerminalDimensions) = AppShellResult.Success(Unit)
        override fun cancel(id: SessionId, reason: CancellationReason) = AppShellResult.Success(Unit)
        override fun close(id: SessionId) = AppShellResult.Success(Unit)
        override fun cachedFrame(id: SessionId, afterVersion: Long?): AppShellResult<CachedSessionFrame?> =
            AppShellResult.Success(null)
    }
}
