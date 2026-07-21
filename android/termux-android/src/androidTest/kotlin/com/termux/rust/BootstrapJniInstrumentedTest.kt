package com.termux.rust

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BootstrapJniInstrumentedTest {
    @Test
    fun completes_bootstrap_lifecycle_through_real_rust_jni() {
        val storage = InMemoryStorage()

        BootstrapController(storage).use { controller ->
            controller.installIfNeeded()
        }

        assertEquals(listOf("isInstalled", "prepareInstall", "install"), storage.calls)
    }

    private class InMemoryStorage : BootstrapStorage {
        val calls = mutableListOf<String>()

        override fun isInstalled(): Boolean {
            calls += "isInstalled"
            return false
        }

        override fun prepareInstall() {
            calls += "prepareInstall"
        }

        override fun install() {
            calls += "install"
        }

        override fun cleanupFailedInstall() {
            calls += "cleanupFailedInstall"
        }
    }
}
