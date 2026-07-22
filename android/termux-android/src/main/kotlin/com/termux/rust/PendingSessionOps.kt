package com.termux.rust

/**
 * Bounded, order-stable, cancellable queue for input/resize operations
 * issued before the service is bound (or before a session attaches).
 *
 * - bounded to [capacity]; the oldest op is dropped when full
 * - FIFO flush; every op runs at most once
 * - ops tagged with a session id are delivered only when the attached
 *   session matches — recreation never delivers stale ops to a new session
 * - [clear] cancels everything (Activity destroy path)
 *
 * Flushing runs on the caller thread but only invokes the typed bookkeeping
 * API — never FFI.
 */
internal class PendingSessionOps(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val ops = ArrayDeque<TaggedOp>()

    val size: Int get() = ops.size

    fun enqueue(
        sessionId: SessionId?,
        op: (TermuxServiceApi, SessionId) -> Unit,
    ) {
        if (ops.size >= capacity) ops.removeFirst()
        ops.addLast(TaggedOp(sessionId, op))
    }

    /** Delivers queued ops to [attached] in FIFO order. Untagged ops go to
     *  the attached session; ops tagged with a different id are dropped. */
    fun flush(api: TermuxServiceApi, attached: SessionId) {
        while (true) {
            val next = ops.removeFirstOrNull() ?: break
            if (next.sessionId == null || next.sessionId == attached) {
                next.op(api, attached)
            }
        }
    }

    fun clear() = ops.clear()

    private class TaggedOp(
        val sessionId: SessionId?,
        val op: (TermuxServiceApi, SessionId) -> Unit,
    )

    private companion object {
        const val DEFAULT_CAPACITY = 64
    }
}
