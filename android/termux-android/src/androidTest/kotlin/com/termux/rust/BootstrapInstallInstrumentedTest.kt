package com.termux.rust

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the install machinery end to end on device: a fixture archive is
 * installed into app-private storage and the extracted artifact actually
 * executes. This validates extraction/permissions only — the repository
 * carries no production bootstrap payload, and on-device installs of the
 * real archive still fail observably with MISSING.
 */
@RunWith(AndroidJUnit4::class)
class BootstrapInstallInstrumentedTest {
    @Test
    fun installed_artifact_executes_from_app_private_storage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "bootstrap-e2e")
        root.deleteRecursively()

        val zipBytes = context.assets.open("e2e-bootstrap.zip").use { it.readBytes() }
        BootstrapInstaller(root, ZipPayloadSource(zipBytes)).installIfNeeded()

        val script = File(root, "usr/bin/hello")
        assertTrue(script.isFile)
        assertTrue(script.canExecute())

        val process = ProcessBuilder("/system/bin/sh", script.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        assertEquals(0, process.waitFor())
        assertEquals("bootstrap-e2e-ok", output)
    }
}
