package com.termux.rust

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Process-local handoff between the exported RunCommandService and
 * TermuxService. Validated requests are enqueued before any attempt to start
 * the service, so a cold start can never drop them; TermuxService drains the
 * queue on create and on every owner-thread drive pass.
 *
 * Each enqueued request is wrapped in an opaque [PendingRequest] token with
 * plain reference identity — deliberately NOT the content equality of
 * [AppExecutionRequest]. Rollback removes exactly the token whose start
 * failed, even when the queue holds byte-identical requests. `poll` is
 * atomic, so each token is delivered exactly once; concurrent Intents
 * serialize naturally. Entries die with the process (START_NOT_STICKY).
 */
internal object PendingRunCommands {
    /** Opaque identity wrapper: equality is reference identity only. */
    internal class PendingRequest internal constructor(
        internal val request: AppExecutionRequest,
    )

    private val queue = ConcurrentLinkedQueue<PendingRequest>()

    fun enqueue(request: AppExecutionRequest): PendingRequest {
        val token = PendingRequest(request)
        queue.add(token)
        return token
    }

    /** Removes exactly this token (used when the service start fails and the
     *  request must not linger as a stale execution). */
    fun remove(token: PendingRequest) {
        queue.remove(token)
    }

    fun drain(): List<AppExecutionRequest> = buildList {
        while (true) {
            add(queue.poll()?.request ?: break)
        }
    }

    /** Test hook. */
    fun clear() = queue.clear()

    /** Test hook: pending tokens in FIFO order, for identity assertions. */
    internal fun pendingTokensForTest(): List<PendingRequest> = queue.toList()

    internal val size: Int get() = queue.size
}
