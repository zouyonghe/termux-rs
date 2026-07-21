package com.termux.rust

interface BootstrapStorage {
    fun isInstalled(): Boolean
    fun prepareInstall()
    fun install()
    fun cleanupFailedInstall()
}

interface NativeBootstrapBridge {
    fun create(): Long
    fun begin(handle: Long, installed: Boolean, prepareSucceeded: Boolean): Int
    fun complete(handle: Long, installSucceeded: Boolean): Int
    fun state(handle: Long): Int
    fun free(handle: Long)
}

internal object NativeStatus {
    const val OK = 0
}

internal enum class BootstrapState(val value: Int) {
    NOT_INSTALLED(0),
    INSTALLING(1),
    INSTALLED(2),
    FAILED(3),
}

class BootstrapController(
    private val storage: BootstrapStorage,
    private val bridge: NativeBootstrapBridge = JniBootstrapBridge,
) : AutoCloseable {
    private var handle = bridge.create().also {
        check(it != 0L) { "Unable to create Rust bootstrap handle" }
    }

    fun installIfNeeded() {
        val handle = requireHandle()
        if (storage.isInstalled()) {
            requireNative(bridge.begin(handle, installed = true, prepareSucceeded = false))
            requireState(BootstrapState.INSTALLED)
            return
        }

        try {
            storage.prepareInstall()
        } catch (error: Throwable) {
            requireNative(bridge.begin(handle, installed = false, prepareSucceeded = false))
            throw error
        }

        requireNative(bridge.begin(handle, installed = false, prepareSucceeded = true))
        requireState(BootstrapState.INSTALLING)

        try {
            storage.install()
        } catch (error: Throwable) {
            val completionStatus = try {
                bridge.complete(handle, installSucceeded = false)
            } finally {
                storage.cleanupFailedInstall()
            }
            requireNative(completionStatus)
            throw error
        }

        requireNative(bridge.complete(handle, installSucceeded = true))
        requireState(BootstrapState.INSTALLED)
    }

    override fun close() {
        val handle = handle
        if (handle != 0L) {
            this.handle = 0
            bridge.free(handle)
        }
    }

    private fun requireHandle(): Long {
        check(handle != 0L) { "BootstrapController is closed" }
        return handle
    }

    private fun requireNative(status: Int) {
        check(status == NativeStatus.OK) { "Rust bootstrap call failed with status $status" }
    }

    private fun requireState(expected: BootstrapState) {
        val actual = BootstrapState.entries.firstOrNull { it.value == bridge.state(requireHandle()) }
        check(actual == expected) { "Expected bootstrap state $expected but was $actual" }
    }
}

internal object JniBootstrapBridge : NativeBootstrapBridge {
    init {
        System.loadLibrary("termux_ffi")
    }

    override fun create(): Long = nativeCreate()

    override fun begin(handle: Long, installed: Boolean, prepareSucceeded: Boolean): Int =
        nativeBegin(handle, installed, prepareSucceeded)

    override fun complete(handle: Long, installSucceeded: Boolean): Int =
        nativeComplete(handle, installSucceeded)

    override fun state(handle: Long): Int = nativeState(handle)

    override fun free(handle: Long) {
        nativeFree(handle)
    }

    private external fun nativeCreate(): Long
    private external fun nativeBegin(handle: Long, installed: Boolean, prepareSucceeded: Boolean): Int
    private external fun nativeComplete(handle: Long, installSucceeded: Boolean): Int
    private external fun nativeState(handle: Long): Int
    private external fun nativeFree(handle: Long)
}
