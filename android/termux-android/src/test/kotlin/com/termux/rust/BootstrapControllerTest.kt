package com.termux.rust

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BootstrapControllerTest {
    @Test
    fun installs_when_storage_is_missing() {
        val storage = FakeStorage(installed = false)
        val bridge = FakeBridge()

        BootstrapController(storage, bridge).use { controller ->
            controller.installIfNeeded()
        }

        assertEquals(listOf("isInstalled", "prepareInstall", "install"), storage.calls)
        assertEquals(BootstrapState.INSTALLED, bridge.state)
        assertEquals(1, bridge.freeCount)
    }

    @Test
    fun skips_install_for_an_existing_bootstrap() {
        val storage = FakeStorage(installed = true)
        val bridge = FakeBridge()

        BootstrapController(storage, bridge).use { controller ->
            controller.installIfNeeded()
        }

        assertEquals(listOf("isInstalled"), storage.calls)
        assertEquals(BootstrapState.INSTALLED, bridge.state)
    }

    @Test
    fun cleans_up_when_installation_fails() {
        val storage = FakeStorage(installed = false, installError = IllegalStateException("install failed"))
        val bridge = FakeBridge()

        BootstrapController(storage, bridge).use { controller ->
            assertFailsWith<IllegalStateException> { controller.installIfNeeded() }
        }

        assertEquals(
            listOf("isInstalled", "prepareInstall", "install", "cleanupFailedInstall"),
            storage.calls
        )
        assertEquals(BootstrapState.FAILED, bridge.state)
    }

    @Test
    fun rejects_calls_after_close() {
        val controller = BootstrapController(FakeStorage(installed = true), FakeBridge())
        controller.close()

        assertFailsWith<IllegalStateException> { controller.installIfNeeded() }
    }

    private class FakeStorage(
        private val installed: Boolean,
        private val installError: Throwable? = null,
    ) : BootstrapStorage {
        val calls = mutableListOf<String>()

        override fun isInstalled(): Boolean {
            calls += "isInstalled"
            return installed
        }

        override fun prepareInstall() {
            calls += "prepareInstall"
        }

        override fun install() {
            calls += "install"
            installError?.let { throw it }
        }

        override fun cleanupFailedInstall() {
            calls += "cleanupFailedInstall"
        }
    }

    private class FakeBridge : NativeBootstrapBridge {
        var state = BootstrapState.NOT_INSTALLED
        var freeCount = 0

        override fun create(): Long = 1

        override fun begin(handle: Long, installed: Boolean, prepareSucceeded: Boolean): Int {
            state = when {
                installed -> BootstrapState.INSTALLED
                prepareSucceeded -> BootstrapState.INSTALLING
                else -> BootstrapState.FAILED
            }
            return NativeStatus.OK
        }

        override fun complete(handle: Long, installSucceeded: Boolean): Int {
            state = if (installSucceeded) BootstrapState.INSTALLED else BootstrapState.FAILED
            return NativeStatus.OK
        }

        override fun state(handle: Long): Int = state.value

        override fun free(handle: Long) {
            freeCount += 1
        }
    }
}
