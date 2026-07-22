package com.termux.rust

interface NativeTerminalSessionBridge {
    fun create(command: String, arguments: List<String>, columns: Int, rows: Int): Long
    fun feedOutput(handle: Long, bytes: ByteArray): Int
    fun writeInput(handle: Long, bytes: ByteArray): Int
    fun pumpOutput(handle: Long): Int
    fun render(handle: Long): ByteArray
    fun resize(handle: Long, columns: Int, rows: Int): Int
    fun tryWait(handle: Long, exitCodeOut: IntArray): Int
    fun terminate(handle: Long): Int
    fun free(handle: Long)
}

class TerminalSessionController(
    command: String,
    arguments: List<String>,
    columns: Int,
    rows: Int,
    private val bridge: NativeTerminalSessionBridge = JniTerminalSessionBridge,
) : AutoCloseable {
    private var handle = bridge.create(command, arguments, columns, rows).also {
        check(it != 0L) { "Unable to create Rust terminal session" }
    }

    fun feedOutput(bytes: ByteArray) = requireSuccess(bridge.feedOutput(requireHandle(), bytes))
    fun writeInput(bytes: ByteArray) = requireSuccess(bridge.writeInput(requireHandle(), bytes))
    fun resize(columns: Int, rows: Int) = requireSuccess(bridge.resize(requireHandle(), columns, rows))
    fun pumpOutput(): Int = bridge.pumpOutput(requireHandle())
    fun render(): ByteArray = bridge.render(requireHandle())

    /** Returns the child exit code once the process has exited; null while running. */
    fun pollExitCode(): Int? {
        val exitCodeOut = IntArray(1)
        return when (val status = bridge.tryWait(requireHandle(), exitCodeOut)) {
            NativeStatus.OK -> exitCodeOut[0]
            NativeStatus.SESSION_RUNNING -> null
            else -> throw IllegalStateException("Rust terminal session tryWait failed with status $status")
        }
    }

    fun terminate() = requireSuccess(bridge.terminate(requireHandle()))

    override fun close() {
        val handle = handle
        if (handle != 0L) {
            this.handle = 0
            bridge.free(handle)
        }
    }

    private fun requireHandle(): Long {
        check(handle != 0L) { "TerminalSessionController is closed" }
        return handle
    }

    private fun requireSuccess(status: Int) {
        check(status == NativeStatus.OK) { "Rust terminal session call failed with status $status" }
    }
}

/**
 * Renders via the two-step native size-query/copy protocol. The snapshot can
 * grow between the size query and the copy (the native side then reports a
 * short/zeroed write); retry with a freshly measured buffer instead of
 * decoding a zero-filled buffer. Returns an empty array when the native side
 * cannot produce a complete snapshot within [maxAttempts].
 */
internal fun renderSnapshotBytes(
    querySize: () -> Int,
    copyInto: (ByteArray) -> Int,
    maxAttempts: Int = 3,
): ByteArray {
    repeat(maxAttempts) {
        val size = querySize()
        if (size <= 0) return ByteArray(0)
        val buffer = ByteArray(size)
        if (copyInto(buffer) == size) return buffer
    }
    return ByteArray(0)
}

internal object JniTerminalSessionBridge : NativeTerminalSessionBridge {
    init { System.loadLibrary("termux_ffi") }

    override fun create(command: String, arguments: List<String>, columns: Int, rows: Int) =
        nativeCreate(command, arguments.toTypedArray(), columns, rows)
    override fun feedOutput(handle: Long, bytes: ByteArray) = nativeFeedOutput(handle, bytes)
    override fun writeInput(handle: Long, bytes: ByteArray) = nativeWriteInput(handle, bytes)
    override fun pumpOutput(handle: Long) = nativePumpOutput(handle)
    override fun render(handle: Long): ByteArray =
        renderSnapshotBytes(
            querySize = { nativeRenderSize(handle) },
            copyInto = { nativeRender(handle, it) },
        )
    override fun resize(handle: Long, columns: Int, rows: Int) = nativeResize(handle, columns, rows)
    override fun tryWait(handle: Long, exitCodeOut: IntArray) = nativeTryWait(handle, exitCodeOut)
    override fun terminate(handle: Long) = nativeTerminate(handle)
    override fun free(handle: Long) = nativeFree(handle)

    private external fun nativeCreate(command: String, arguments: Array<String>, columns: Int, rows: Int): Long
    private external fun nativeFeedOutput(handle: Long, bytes: ByteArray): Int
    private external fun nativeWriteInput(handle: Long, bytes: ByteArray): Int
    private external fun nativePumpOutput(handle: Long): Int
    private external fun nativeRenderSize(handle: Long): Int
    private external fun nativeRender(handle: Long, output: ByteArray): Int
    private external fun nativeResize(handle: Long, columns: Int, rows: Int): Int
    private external fun nativeTryWait(handle: Long, exitCodeOut: IntArray): Int
    private external fun nativeTerminate(handle: Long): Int
    private external fun nativeFree(handle: Long)
}
