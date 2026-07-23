package com.termux.rust

import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

/**
 * Permission/notification/background-limits hardening evidence across the API
 * matrix (24 baseline, 26 channels, 31 background-start limits, 33+
 * notification permission). No system permission success is ever faked.
 */
@RunWith(RobolectricTestRunner::class)
class PermissionHardeningTest {
    @AfterTest
    fun reset() {
        TermuxService.registryFactory = TermuxService.defaultNoEnvRegistryFactory
    }

    // --- manifest / least-privilege surface ---------------------------------

    @Test
    @Config(sdk = [24])
    fun manifest_exposes_only_the_adapter_with_signature_permission() {
        val pm = RuntimeEnvironment.getApplication().packageManager
        val pkg = pm.getPackageInfo(
            RuntimeEnvironment.getApplication().packageName,
            PackageManager.GET_SERVICES or PackageManager.GET_PERMISSIONS,
        )

        val services = pkg.services!!.associateBy { it.name }
        val runCommand = services.getValue("com.termux.rust.RunCommandService")
        assertTrue(runCommand.exported)
        assertEquals("com.termux.rust.permission.RUN_COMMAND", runCommand.permission)

        val termux = services.getValue("com.termux.rust.TermuxService")
        assertFalse(termux.exported)

        val declared = pkg.permissions!!.single { it.name == "com.termux.rust.permission.RUN_COMMAND" }
        assertEquals("signature", protectionToString(declared.protectionLevel))
    }

    @Test
    @Config(sdk = [24])
    fun manifest_declares_notification_and_foreground_permissions() {
        val pm = RuntimeEnvironment.getApplication().packageManager
        val pkg = pm.getPackageInfo(
            RuntimeEnvironment.getApplication().packageName,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES,
        )
        val requested = pkg.requestedPermissions!!.toSet()
        assertTrue("android.permission.POST_NOTIFICATIONS" in requested)
        assertTrue("android.permission.FOREGROUND_SERVICE" in requested)
        assertTrue("android.permission.FOREGROUND_SERVICE_SPECIAL_USE" in requested)
    }

    // --- Binder boundary ------------------------------------------------------

    @Test
    @Config(sdk = [24])
    fun binder_is_not_reachable_from_outside_the_app() {
        // The Binder facade lives on a non-exported service: the system
        // itself rejects external binds. Assert the enforcement mechanism,
        // not a reimplementation of it.
        val info = serviceInfo("com.termux.rust.TermuxService")
        assertFalse(info.exported)
    }

    // --- notification permission denial -------------------------------------

    @Test
    @Config(sdk = [33])
    fun denied_notification_permission_degrades_observably_without_crash() {
        shadowOf(notificationManager()).setNotificationsEnabled(false)
        TermuxService.registryFactory = {
            SessionRegistry(engineFactory = { _, _ -> NoopEngine })
        }
        val service = Robolectric.buildService(TermuxService::class.java).create().get()
        service.stopDriveLoopForTest()
        val id = (service.core.api.createSession(request()) as AppShellResult.Success).value
        service.core.registry.drive(id)

        // Live work still promotes the service (FGS semantics do not depend
        // on shade permission), but the denial is observable and never faked.
        service.runDriveLoopOnceForTest()
        assertTrue(service.foregroundActiveForTest)
        assertTrue(service.notificationPermissionDeniedForTest)

        shadowOf(notificationManager()).setNotificationsEnabled(true)
        service.stopDriveLoopForTest()
        Robolectric.buildService(TermuxService::class.java).destroy()
    }

    @Test
    @Config(sdk = [33])
    fun granted_notification_permission_posts_without_denial_flag() {
        shadowOf(notificationManager()).setNotificationsEnabled(true)
        TermuxService.registryFactory = {
            SessionRegistry(engineFactory = { _, _ -> NoopEngine })
        }
        val service = Robolectric.buildService(TermuxService::class.java).create().get()
        service.stopDriveLoopForTest()
        val id = (service.core.api.createSession(request()) as AppShellResult.Success).value
        service.core.registry.drive(id)

        service.runDriveLoopOnceForTest()
        assertTrue(service.foregroundActiveForTest)
        assertFalse(service.notificationPermissionDeniedForTest)

        Robolectric.buildService(TermuxService::class.java).destroy()
    }

    // --- foreground deadline --------------------------------------------------

    @Test
    @Config(sdk = [31])
    fun first_live_session_promotes_foreground_within_one_drive_pass() {
        // API 31+ deadlines (startForeground within ~10s of promotion demand)
        // are met by construction: promotion happens in the same drive pass
        // that observes live work, not on a later timer.
        TermuxService.registryFactory = {
            SessionRegistry(engineFactory = { _, _ -> NoopEngine })
        }
        val service = Robolectric.buildService(TermuxService::class.java).create().get()
        service.stopDriveLoopForTest()
        val id = (service.core.api.createSession(request()) as AppShellResult.Success).value
        service.core.registry.drive(id)

        assertFalse(service.foregroundActiveForTest)
        service.runDriveLoopOnceForTest()
        assertTrue(service.foregroundActiveForTest)

        Robolectric.buildService(TermuxService::class.java).destroy()
    }

    // --- background behavior --------------------------------------------------

    @Test
    @Config(sdk = [26])
    fun empty_registry_idles_and_never_promotes_foreground() {
        TermuxService.registryFactory = {
            SessionRegistry(engineFactory = { _, _ -> NoopEngine })
        }
        val service = Robolectric.buildService(TermuxService::class.java).create().get()
        service.stopDriveLoopForTest()

        service.runDriveLoopOnceForTest()
        assertFalse(service.foregroundActiveForTest)

        Robolectric.buildService(TermuxService::class.java).destroy()
    }

    private fun request() = AppExecutionRequest(
        origin = RequestOrigin.Internal,
        executable = "/system/bin/sh",
        target = ExecutionTarget.TERMINAL_SESSION,
        terminalSize = TerminalDimensions(80, 24),
    )

    @Test
    @Config(sdk = [24])
    fun termux_service_declares_special_use_type_with_subtype_property() {
        // Robolectric's manifest parser does not surface <property> elements,
        // so assert the declaration at source level (unit tests run with the
        // module dir as working directory).
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()

        assertTrue(
            manifest.contains("""android:foregroundServiceType="specialUse""""),
            "specialUse type missing",
        )
        // API 34+ specialUse requires the subtype property; exact name/value.
        assertTrue(
            manifest.contains("""android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE""""),
            "subtype property name missing",
        )
        assertTrue(
            manifest.contains("""android:value="terminal session execution""""),
            "subtype property value missing",
        )

        // The service itself remains non-exported (system-level Binder rejection).
        assertFalse(serviceInfo("com.termux.rust.TermuxService").exported)
    }

    private fun notificationManager(): android.app.NotificationManager =
        RuntimeEnvironment.getApplication().getSystemService(android.app.NotificationManager::class.java)

    private fun serviceInfo(name: String): ServiceInfo {
        val pm = RuntimeEnvironment.getApplication().packageManager
        return pm.getPackageInfo(
            RuntimeEnvironment.getApplication().packageName,
            PackageManager.GET_SERVICES,
        ).services!!.single { it.name == name }
    }

    private fun protectionToString(level: Int): String = when (level and 0xf) {
        0 -> "normal"
        1 -> "dangerous"
        2 -> "signature"
        else -> "other"
    }
}

private object NoopEngine : SessionEngine {
    override fun writeInput(input: SessionInput) = Unit
    override fun resize(dimensions: TerminalDimensions) = Unit
    override fun terminate() = Unit
    override fun pollTermination(): SessionTermination? = null
    override fun renderFrame(): Pair<Long, ByteArray>? = null
    override fun close() = Unit
}
