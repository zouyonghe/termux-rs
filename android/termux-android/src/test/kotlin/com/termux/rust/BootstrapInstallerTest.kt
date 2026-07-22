package com.termux.rust

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BootstrapInstallerTest {
    @Test
    fun fresh_install_extracts_marks_ready_and_is_idempotent() {
        val root = tempRoot()
        val installer = BootstrapInstaller(root, ZipPayloadSource(goodZip()))

        assertEquals(BootstrapInstallState.NOT_INSTALLED, installer.state())
        installer.installIfNeeded()

        assertEquals(BootstrapInstallState.READY, installer.state())
        assertTrue(File(root, "usr/bin/tool").isFile)
        assertTrue(File(root, "usr/bin/tool").canExecute())
        assertTrue(File(root, "usr/lib/data.txt").isFile)
        assertTrue(File(root, "usr/lib/data.txt").canRead())

        // Repeat install is a no-op fast path.
        installer.installIfNeeded()
        assertEquals(BootstrapInstallState.READY, installer.state())
    }

    @Test
    fun restart_fast_path_validates_marker_against_metadata_not_trust() {
        val root = tempRoot()
        val zip = goodZip()
        BootstrapInstaller(root, ZipPayloadSource(zip)).installIfNeeded()

        // New process: metadata (version+sha) is still bundled with the APK,
        // so READY is proven without payload bytes.
        val restarted = BootstrapInstaller(
            root,
            MetadataOnlyPayloadSource(version = "test-1", expectedSha256 = zipSha(zip)),
        )
        assertEquals(BootstrapInstallState.READY, restarted.state())
        restarted.installIfNeeded()
        assertEquals(BootstrapInstallState.READY, restarted.state())

        // A source WITHOUT metadata must never treat the marker as READY.
        val blind = BootstrapInstaller(root, MissingPayloadSource)
        assertEquals(BootstrapInstallState.NOT_INSTALLED, blind.state())
    }

    @Test
    fun state_never_uses_process_cache_to_mask_a_broken_marker() {
        val root = tempRoot()
        val installer = BootstrapInstaller(root, ZipPayloadSource(goodZip()))
        installer.installIfNeeded()
        assertEquals(BootstrapInstallState.READY, installer.state())

        // Marker corrupted or deleted externally: READY must not survive.
        File(root, "usr/.bootstrap-meta").writeText("version=\nsha256=bad\n")
        assertEquals(BootstrapInstallState.NOT_INSTALLED, installer.state())

        installer.installIfNeeded()
        assertEquals(BootstrapInstallState.READY, installer.state())
        assertTrue(File(root, "usr/.bootstrap-meta").delete())
        assertEquals(BootstrapInstallState.NOT_INSTALLED, installer.state())
    }

    @Test
    fun missing_payload_is_an_observable_failure_not_a_fake_success() {
        val root = tempRoot()
        val installer = BootstrapInstaller(root, MissingPayloadSource)

        val error = assertFailsWith<BootstrapInstallException> { installer.installIfNeeded() }
        assertEquals(BootstrapFailure.MISSING, error.failure)
        assertEquals(BootstrapInstallState.FAILED, installer.state())
        assertFalse(File(root, "usr").exists())
    }

    @Test
    fun concurrent_installs_single_flight() {
        val root = tempRoot()
        val installer = BootstrapInstaller(root, ZipPayloadSource(goodZip()))

        val errors = mutableListOf<Throwable>()
        val threads = (1..8).map {
            Thread {
                try {
                    installer.installIfNeeded()
                } catch (error: Throwable) {
                    synchronized(errors) { errors += error }
                }
            }
        }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)

        assertTrue(errors.isEmpty(), "concurrent installs failed: $errors")
        assertEquals(BootstrapInstallState.READY, installer.state())
        assertTrue(File(root, "usr/bin/tool").isFile)
    }

    @Test
    fun corrupt_payload_keeps_previous_healthy_install_and_allows_retry() {
        val root = tempRoot()
        BootstrapInstaller(root, ZipPayloadSource(goodZip())).installIfNeeded()
        val healthyMarker = File(root, "usr/.bootstrap-meta").readText()

        val failing = BootstrapInstaller(root, ZipPayloadSource("not-a-zip".encodeToByteArray()))
        val error = assertFailsWith<BootstrapInstallException> { failing.installIfNeeded() }
        assertEquals(BootstrapFailure.CORRUPT, error.failure)

        // Old install untouched and still reported READY.
        assertTrue(File(root, "usr/bin/tool").isFile)
        assertEquals(healthyMarker, File(root, "usr/.bootstrap-meta").readText())

        // Retry with a good payload succeeds.
        val zip = goodZip()
        BootstrapInstaller(root, ZipPayloadSource(zip)).installIfNeeded()
        assertEquals(BootstrapInstallState.READY, installerState(root, zip))
    }

    @Test
    fun rejects_traversal_symlink_duplicate_and_special_entries() {
        listOf(
            "absolute" to evilZip(absoluteEntry = true),
            "parent traversal" to evilZip(parentEntry = true),
            "symlink" to evilZip(symlinkEntry = true),
            "duplicate" to evilZip(duplicateEntry = true),
            "special device" to evilZip(deviceEntry = true),
        ).forEach { (name, zip) ->
            val root = tempRoot()
            val installer = BootstrapInstaller(root, ZipPayloadSource(zip))
            val error = assertFailsWith<BootstrapInstallException>("$name must be rejected") {
                installer.installIfNeeded()
            }
            assertEquals(BootstrapFailure.UNSUPPORTED, error.failure, name)
            // Nothing escapes the root and nothing is published.
            assertFalse(File(root, "usr").exists(), name)
            assertFalse(File(root.parentFile, "escape.txt").exists(), name)
        }
    }

    @Test
    fun enforces_entry_count_and_total_size_limits() {
        val root = tempRoot()
        val installer = BootstrapInstaller(
            root,
            ZipPayloadSource(goodZip(entries = 20)),
            limits = BootstrapInstallLimits(maxEntries = 10, maxTotalBytes = 1 shl 20),
        )
        val error = assertFailsWith<BootstrapInstallException> { installer.installIfNeeded() }
        assertEquals(BootstrapFailure.UNSUPPORTED, error.failure)
    }

    @Test
    fun cleanup_after_failure_is_idempotent() {
        val root = tempRoot()
        val installer = BootstrapInstaller(root, ZipPayloadSource("junk".encodeToByteArray()))
        assertFailsWith<BootstrapInstallException> { installer.installIfNeeded() }

        installer.cleanupFailedInstall()
        installer.cleanupFailedInstall()
        assertFalse(File(root, "staging").exists())
    }

    @Test
    fun crash_between_renames_restores_healthy_backup_without_payload() {
        val root = tempRoot()
        val zip = goodZip()
        BootstrapInstaller(root, ZipPayloadSource(zip)).installIfNeeded()

        // Simulate the crash point: `usr` renamed to `usr.old`, process died
        // before staging moved in.
        assertTrue(File(root, "usr").renameTo(File(root, "usr.old")))
        assertFalse(File(root, "usr").exists())

        // Next start recovers the previous healthy install WITHOUT opening
        // any payload, then reports READY.
        val recovered = BootstrapInstaller(
            root,
            MetadataOnlyPayloadSource(version = "test-1", expectedSha256 = zipSha(zip)),
        )
        recovered.installIfNeeded()

        assertEquals(BootstrapInstallState.READY, recovered.state())
        assertTrue(File(root, "usr/bin/tool").isFile)
        assertFalse(File(root, "usr.old").exists())
    }

    @Test
    fun crash_after_publish_removes_stale_backup_once_live_is_healthy() {
        val root = tempRoot()
        val zip = goodZip()
        BootstrapInstaller(root, ZipPayloadSource(zip)).installIfNeeded()

        // Simulate crash after new `usr` landed but before `usr.old` cleanup:
        // plant a stale backup alongside a healthy live.
        val stale = File(root, "usr.old")
        stale.mkdirs()
        File(stale, ".bootstrap-meta").writeText("version=old\nsha256=deadbeef\n")

        val installer = BootstrapInstaller(
            root,
            MetadataOnlyPayloadSource(version = "test-1", expectedSha256 = zipSha(zip)),
        )
        installer.installIfNeeded()

        assertEquals(BootstrapInstallState.READY, installer.state())
        assertTrue(File(root, "usr/bin/tool").isFile)
        assertFalse(stale.exists())
    }

    @Test
    fun cross_instance_lock_runs_extraction_exactly_once() {
        val root = tempRoot()
        val zip = goodZip()
        val source = CountingPayloadSource(zip)
        val first = BootstrapInstaller(root, source)
        val second = BootstrapInstaller(root, source)

        val threads = (1..6).map { index ->
            Thread {
                (if (index % 2 == 0) first else second).installIfNeeded()
            }
        }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)

        assertEquals(1, source.openCount)
        assertEquals(BootstrapInstallState.READY, first.state())
        assertEquals(BootstrapInstallState.READY, second.state())
    }

    @Test
    fun nul_entry_name_is_rejected_without_harming_the_healthy_install() {
        val root = tempRoot()
        val zip = goodZip()
        BootstrapInstaller(root, ZipPayloadSource(zip)).installIfNeeded()
        val healthyMarker = File(root, "usr/.bootstrap-meta").readText()

        val attacker = BootstrapInstaller(root, ZipPayloadSource(evilZip(nulEntry = true)))
        val error = assertFailsWith<BootstrapInstallException> { attacker.installIfNeeded() }

        assertEquals(BootstrapFailure.UNSUPPORTED, error.failure)
        assertEquals(BootstrapInstallState.FAILED, attacker.state())
        // The previous healthy install is untouched and still proves READY.
        assertTrue(File(root, "usr/bin/tool").isFile)
        assertEquals(healthyMarker, File(root, "usr/.bootstrap-meta").readText())
        assertEquals(
            BootstrapInstallState.READY,
            BootstrapInstaller(
                root,
                MetadataOnlyPayloadSource(version = "test-1", expectedSha256 = zipSha(zip)),
            ).state(),
        )
    }

    @Test
    fun ancestor_and_type_conflicts_are_unsupported_not_storage() {
        listOf(
            "file then child" to conflictZip(fileFirst = true),
            "dir then file" to conflictZip(fileFirst = false),
        ).forEach { (name, zip) ->
            val root = tempRoot()
            val installer = BootstrapInstaller(root, ZipPayloadSource(zip))
            val error = assertFailsWith<BootstrapInstallException>("$name") {
                installer.installIfNeeded()
            }
            assertEquals(BootstrapFailure.UNSUPPORTED, error.failure, name)
            assertFalse(File(root, "usr").exists(), name)
        }
    }

    @Test
    fun permission_failure_never_publishes_ready() {
        val root = tempRoot()
        // Make the root unwritable: extraction cannot write, install must
        // fail with STORAGE and never reach READY.
        root.setWritable(false, false)
        try {
            val installer = BootstrapInstaller(root, ZipPayloadSource(goodZip()))
            val error = assertFailsWith<BootstrapInstallException> { installer.installIfNeeded() }
            assertTrue(error.failure == BootstrapFailure.STORAGE || error.failure == BootstrapFailure.CORRUPT)
            assertTrue(installer.state() != BootstrapInstallState.READY)
        } finally {
            root.setWritable(true, false)
        }
    }

    private fun installerState(root: File, zip: ByteArray): BootstrapInstallState =
        BootstrapInstaller(
            root,
            MetadataOnlyPayloadSource(version = "test-1", expectedSha256 = zipSha(zip)),
        ).state()

    private fun zipSha(zip: ByteArray): String =
        ZipPayloadSource(zip).expectedSha256!!

    private class CountingPayloadSource(
        private val zip: ByteArray,
    ) : BootstrapPayloadSource {
        @Volatile
        var openCount = 0
        override val version = "test-1"
        override val expectedSha256: String = ZipPayloadSource(zip).expectedSha256!!
        override fun open(): java.io.InputStream {
            openCount += 1
            return zip.inputStream()
        }
    }

    private fun tempRoot(): File =
        File.createTempFile("bootstrap-test", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }

    // Fixtures are assembled in ZipFixture.kt (test-only zip builder).
}
