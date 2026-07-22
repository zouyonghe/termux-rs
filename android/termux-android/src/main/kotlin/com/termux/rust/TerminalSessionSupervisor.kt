package com.termux.rust

/**
 * Owns one Rust-backed terminal session and its lifecycle state machine.
 * Activity (and later a TermuxService handoff) talks to this boundary only;
 * all FFI access is serialized by confining calls to the caller's thread —
 * the current owner drives frames from the main thread, and no background
 * thread may call in because the Rust FFI requires exclusive session access.
 *
 * States: RUNNING -> EXITED (child reaped, exit code known) -> CLOSED.
 * All lifecycle operations are idempotent.
 */
internal class TerminalSessionSupervisor(
    command: String,
    arguments: List<String>,
    columns: Int,
    rows: Int,
    bridge: NativeTerminalSessionBridge = JniTerminalSessionBridge,
) : AutoCloseable {
    private val session = TerminalSessionController(command, arguments, columns, rows, bridge)

    var exitCode: Int? = null
        private set
    val exited: Boolean get() = exitCode != null
    private var terminated = false
    private var closed = false

    /** Pumps pending output and polls child exit. Returns snapshot bytes to
     *  render this frame, or null to skip the frame (no complete snapshot or
     *  session closed). The final frame is still rendered after exit so the
     *  last child output stays visible. */
    fun pumpFrame(): ByteArray? {
        if (closed) return null
        if (!exited) {
            when (val status = session.pumpOutput()) {
                NativeStatus.OK, NativeStatus.SESSION_RUNNING -> Unit
                NativeStatus.SESSION_OUTPUT_CLOSED -> pollExit()
                else -> throw IllegalStateException("Rust terminal session pump failed with status $status")
            }
            pollExit()
        }
        val rendered = session.render()
        return if (rendered.isEmpty()) null else rendered
    }

    fun writeInput(bytes: ByteArray) {
        if (!closed && !terminated && !exited) session.writeInput(bytes)
    }

    fun resize(columns: Int, rows: Int) {
        if (!closed && !terminated && !exited) session.resize(columns, rows)
    }

    /** Idempotent: safe to call repeatedly and after natural child exit. */
    fun terminate() {
        if (closed || terminated || exited) return
        terminated = true
        runCatching { session.terminate() }
        pollExit()
    }

    /** Idempotent: terminates a still-running child exactly once, then frees
     *  the handle. Termination happens before the closed flag is set so the
     *  child is never freed while still running. */
    override fun close() {
        if (closed) return
        terminate()
        closed = true
        session.close()
    }

    private fun pollExit() {
        if (exitCode == null) {
            exitCode = session.pollExitCode()
        }
    }
}
