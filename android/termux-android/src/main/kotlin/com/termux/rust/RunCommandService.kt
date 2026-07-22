package com.termux.rust

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * Exported RUN_COMMAND adapter. The manifest signature permission is the
 * caller-identity boundary; this class performs no FFI and never touches the
 * registry directly. Validated requests go onto [PendingRunCommands] before
 * the service start is attempted, so cold-start delivery is guaranteed by
 * TermuxService's drain — never by reading a maybe-absent instance.
 */
class RunCommandService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (val parsed = RunCommandIntentParser.parse(intent)) {
            is AppShellResult.Failure -> {
                Log.w(TAG, "rejected run-command intent: ${parsed.error.code}")
            }
            is AppShellResult.Success -> {
                val token = PendingRunCommands.enqueue(parsed.value)
                try {
                    // API 31+: this can throw IllegalStateException when the
                    // app is in the background. The enqueued token is then
                    // rolled back so it can never fire later as a stale
                    // command; phase-1 requires a foreground caller or an
                    // exemption.
                    termuxServiceStarter(this)
                } catch (error: IllegalStateException) {
                    PendingRunCommands.remove(token)
                    Log.e(TAG, "cannot start termux service in background; request dropped", error)
                }
            }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    internal companion object {
        const val TAG = "RunCommandService"

        val defaultTermuxServiceStarter: (Service) -> Unit = { service ->
            service.startService(Intent(service, TermuxService::class.java))
        }

        /** Test hook: force start failures. Reset in @AfterTest. */
        internal var termuxServiceStarter = defaultTermuxServiceStarter
    }
}
