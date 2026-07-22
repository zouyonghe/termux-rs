package com.termux.rust

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Same-app typed Binder facade. Methods delegate to the registry's
 *  bookkeeping API — no FFI runs on the caller's thread. */
internal class TermuxServiceBinder(
    api: TermuxServiceApi,
) : Binder(), TermuxServiceApi by api

/**
 * Non-exported service owning the multi-session registry and every Rust
 * session. All FFI happens on a dedicated owner [HandlerThread] via
 * [SessionRegistry.driveAll]; caller threads (main, Binder) only touch the
 * typed [TermuxServiceApi] bookkeeping API.
 *
 * Foreground state and drive frequency follow [ForegroundPolicy] after each
 * drive pass: the first live session promotes the service to foreground, the
 * last one demotes it; an empty registry idles at a low poll rate.
 */
class TermuxService : Service() {
    private var ownerThread: HandlerThread? = null
    private var ownerHandler: Handler? = null
    private var foregroundActive = false
    private var bootstrapExecutor: java.util.concurrent.ExecutorService? = null

    internal lateinit var core: TermuxServiceCore
        private set

    /** Test hook: whether the service currently holds foreground state. */
    internal val foregroundActiveForTest: Boolean get() = foregroundActive

    /** Created lazily: onBind can only happen after onCreate, when [core]
     *  already exists. */
    private val binder by lazy { TermuxServiceBinder(core.api) }

    private val driveLoop = object : Runnable {
        override fun run() {
            var nextDelay = ForegroundPolicy.IDLE_INTERVAL_MS
            try {
                drainPendingRunCommands()
                core.registry.driveAll()
                core.registry.reapTerminals()
                val decision = ForegroundPolicy.decide(
                    summary = core.registry.summary(),
                    sessionCount = (core.registry.sessions() as AppShellResult.Success).value.size,
                )
                applyForeground(decision)
                nextDelay = decision.driveIntervalMs
            } catch (error: Throwable) {
                Log.e(TAG, "session drive failed; continuing", error)
            } finally {
                ownerHandler?.postDelayed(this, nextDelay)
            }
        }
    }

    /** Test hook: runs one drive-loop iteration synchronously. */
    internal fun runDriveLoopOnceForTest() = driveLoop.run()

    /** Test hook: stops the background drive loop AND waits for any
     *  in-flight pass to finish, so manual drives cannot race it. */
    internal fun stopDriveLoopForTest() {
        ownerHandler?.removeCallbacks(driveLoop)
        val idle = CountDownLatch(1)
        ownerHandler?.post { idle.countDown() }
        idle.await(2, TimeUnit.SECONDS)
    }

    override fun onCreate() {
        super.onCreate()
        val thread = HandlerThread("termux-service-owner")
        thread.start()
        ownerThread = thread
        ownerHandler = Handler(thread.looper)
        val bootstrap = bootstrapInstallerFactory(this)
        core = TermuxServiceCore(registryFactory(), { message, error ->
            Log.e(TAG, message, error)
        }, bootstrap)
        createNotificationChannel()
        instance = this
        drainPendingRunCommands()
        ownerHandler?.post(driveLoop)
        // Bootstrap I/O never runs on main/Binder or the session/FFI owner
        // thread: it gets its own single-thread executor so a large unpack
        // cannot stall driveAll, input, rendering, or termination.
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "termux-bootstrap")
        }
        bootstrapExecutor = executor
        executor.execute {
            runCatching { bootstrap.installIfNeeded() }
                .onFailure { Log.w(TAG, "bootstrap install failed", it) }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        ownerHandler?.removeCallbacks(driveLoop)
        val handler = ownerHandler
        if (::core.isInitialized && handler != null) {
            // Deterministic shutdown: run the cancel/drive/close sequence on
            // the owner thread and wait for it before the thread exits.
            val done = CountDownLatch(1)
            handler.post {
                try {
                    core.destroy()
                } finally {
                    done.countDown()
                }
            }
            if (!done.await(destroyTimeoutMs, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "timed out waiting for session shutdown")
            }
        }
        if (foregroundActive) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundActive = false
        }
        bootstrapExecutor?.shutdownNow()
        bootstrapExecutor = null
        ownerThread?.quitSafely()
        ownerThread = null
        ownerHandler = null
        instance = null
        super.onDestroy()
    }

    private fun applyForeground(decision: ForegroundPolicy.Decision) {
        // API 33+: a denied POST_NOTIFICATIONS never crashes the service and
        // is always observable — we never pretend the notification posted.
        notificationPermissionDenied =
            decision.foregroundRequired && !notificationGate.areNotificationsEnabled(this)
        if (decision.foregroundRequired && !foregroundActive) {
            startForeground(NOTIFICATION_ID, buildNotification(decision.sessionCount))
            foregroundActive = true
        } else if (!decision.foregroundRequired && foregroundActive) {
            // REMOVE dismisses the notification: no stale "N sessions active"
            // corpse is left in the shade after the last live session ends.
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundActive = false
        } else if (decision.foregroundRequired && foregroundActive && !notificationPermissionDenied) {
            // Keep the session count current; reposting the same id is
            // idempotent for the system. Skipped entirely while denied.
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification(decision.sessionCount))
        }
    }

    /** Test/observability hook: true while live work needs foreground but
     *  POST_NOTIFICATIONS is denied. */
    internal val notificationPermissionDeniedForTest: Boolean get() = notificationPermissionDenied

    private var notificationPermissionDenied = false

    /** Delivers every validated run-command waiting in the handoff queue.
     *  Called on create and after each drive pass on the owner thread. */
    private fun drainPendingRunCommands() {
        PendingRunCommands.drain().forEach { request ->
            when (val created = core.api.createSession(request)) {
                is AppShellResult.Failure ->
                    Log.w(TAG, "pending session rejected: ${created.error.code}")
                is AppShellResult.Success ->
                    Log.i(TAG, "pending session ${created.value.value} accepted")
            }
        }
    }

    private fun buildNotification(sessionCount: Int): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("termux-rs sessions")
            .setContentText("$sessionCount session(s) active")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Terminal sessions",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        // API 24-25: channels do not exist; Notification.Builder(context)
        // without a channel id is used via the deprecation-tolerant call
        // below. API 33+: POST_NOTIFICATIONS is a runtime permission the app
        // must request from an Activity; the foreground notification still
        // appears in the task manager without it.
    }

    internal companion object {
        const val TAG = "TermuxService"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "termux_sessions"
        const val BOOTSTRAP_ASSET = "bootstrap/arm64-v8a.zip"

        /** Test hook: shorten the destroy wait. */
        internal var destroyTimeoutMs = 5_000L

        /** Notification permission boundary; tests replace with a fake. */
        internal var notificationGate: NotificationPermissionGate = NotificationPermissionGate.System

        /** Same-app access for the exported adapter; null when destroyed. */
        @Volatile
        internal var instance: TermuxService? = null

        /** Test hook: swap the registry (fake engines) before service create. */
        internal var registryFactory: () -> SessionRegistry = {
            SessionRegistry(engineFactory = { request, id -> RustSessionEngine(request, id) })
        }

        /** Production bootstrap installer boundary. Tests inject a fixture
         *  payload here; the default stays the real asset source (which is
         *  MISSING until a production archive ships). */
        internal var bootstrapInstallerFactory: (android.content.Context) -> BootstrapInstaller = { context ->
            BootstrapInstaller(
                root = context.filesDir,
                payload = AssetBootstrapPayloadSource(context.assets, BOOTSTRAP_ASSET),
            )
        }
    }
}
