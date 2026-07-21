package com.termux.rust

interface NativeTerminalSessionBridge {
    fun create(command: String, arguments: List<String>, columns: Int, rows: Int): Long
    fun feedOutput(handle: Long, bytes: ByteArray): Int
    fun writeInput(handle: Long, bytes: ByteArray): Int
    fun pumpOutput(handle: Long): Int
    fun render(handle: Long): ByteArray
    fun resize(handle: Long, columns: Int, rows: Int): Int
    fun tryWait(handle: Long): Int
    fun terminate(handle: Long): Int
    fun free(handle: Long)
}

class TerminalSessionController(
    command: String,
    arguments: List<String>,
    columns: Int,
    rows: Int,
    private val bridge: NativeTerminalSessionBridge,
) : AutoCloseable {
    private var handle = bridge.create(command, arguments, columns, rows).also {
        check(it != 0L) { "Unable to create Rust terminal session" }
    }

    fun feedOutput(bytes: ByteArray) = requireSuccess(bridge.feedOutput(requireHandle(), bytes))
    fun writeInput(bytes: ByteArray) = requireSuccess(bridge.writeInput(requireHandle(), bytes))
    fun resize(columns: Int, rows: Int) = requireSuccess(bridge.resize(requireHandle(), columns, rows))
    fun pumpOutput(): Int = bridge.pumpOutput(requireHandle())
    fun render(): ByteArray = bridge.render(requireHandle())
    fun tryWait(): Int = bridge.tryWait(requireHandle())
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
