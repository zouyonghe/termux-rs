package com.termux.rust

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.ViewTreeObserver
import android.widget.TextView

/**
 * Renders one terminal session owned by [TermuxService]. The Activity never
 * touches Rust directly: it binds the service, reads frames via
 * `cachedFrame` and lifecycle via `session`, and forwards input/resize
 * through the typed API. Unbinding (including configuration changes) never
 * cancels the session; only an explicit `close` ends it.
 */
class TerminalActivity : Activity() {
    private lateinit var surface: TextView
    private val handler = Handler(Looper.getMainLooper())
    internal var api: TermuxServiceApi? = null
    internal var sessionId: SessionId? = null
    private val pendingOps = PendingSessionOps()
    private var lastRenderedVersion = -1L
    private var refreshActive = false

    internal var terminalColumns = 80
        private set
    internal var terminalRows = 24
        private set

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            api = service as TermuxServiceApi
            attachSession()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            attachCancelled = true
            api = null
        }
    }

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        val measured = measureTerminalSize()
        if (measured != null && (measured.first != terminalColumns || measured.second != terminalRows)) {
            terminalColumns = measured.first
            terminalRows = measured.second
            dispatchOp { service, id ->
                service.resize(id, TerminalDimensions(terminalColumns, terminalRows))
            }
        }
    }

    private val refresh = object : Runnable {
        override fun run() {
            try {
                refreshOnce()
            } catch (error: Exception) {
                Log.e(TAG, "terminal refresh failed; continuing", error)
            } finally {
                if (refreshActive) {
                    handler.postDelayed(this, REFRESH_INTERVAL_MS)
                }
            }
        }
    }

    internal var exitBanner: String? = null

    private fun refreshOnce() {
        val service = api ?: return
        val id = sessionId ?: return
        val frame = (service.cachedFrame(id, lastRenderedVersion) as? AppShellResult.Success)?.value
        if (frame != null) {
            lastRenderedVersion = frame.version
            surface.text = TerminalTextRenderer.render(TerminalSnapshotCodec.decode(frame.payload))
            // A re-render must never wipe an already-announced exit banner.
            exitBanner?.let { surface.append(it) }
        }
        val snapshot = (service.session(id) as? AppShellResult.Success)?.value ?: return
        val termination = snapshot.termination
        if (exitBanner == null && termination is SessionTermination.ProcessExited) {
            exitBanner = "\n[process exited with code ${termination.code}]"
            surface.append(exitBanner!!)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = savedInstanceState?.getLong(KEY_SESSION_ID, 0L)
            ?.takeIf { it > 0 }
            ?.let(::SessionId)
        surface = TextView(this).apply {
            text = "Starting terminal..."
            isFocusableInTouchMode = true
            requestFocus()
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) writeKey(keyCode, event) else false
            }
        }
        setContentView(surface)
        surface.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        startService(Intent(this, TermuxService::class.java))
        bindService(Intent(this, TermuxService::class.java), connection, BIND_AUTO_CREATE)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        sessionId?.let { outState.putLong(KEY_SESSION_ID, it.value) }
    }

    /** Reattach to the restored session if it still exists; create exactly
     *  one otherwise. Recreation never duplicates or cancels sessions.
     *  Queued pre-bind ops flush here, tagged to the attached session. */
    internal fun attachSession() {
        val service = api ?: return
        val previousId = sessionId
        val restored = sessionId
        if (restored != null && service.session(restored) is AppShellResult.Success) {
            pendingOps.flush(service, restored)
            return
        }
        val request = AppExecutionRequest(
            origin = RequestOrigin.Internal,
            executable = "/system/bin/sh",
            target = ExecutionTarget.TERMINAL_SESSION,
            terminalSize = TerminalDimensions(terminalColumns, terminalRows),
        )
        when (val created = service.createSession(request)) {
            is AppShellResult.Success -> {
                sessionId = created.value
                attachRetryCount = 0
                // Any attached id change clears the old banner; it is rebuilt
                // from the NEW session's own termination only.
                if (sessionId != previousId) {
                    exitBanner = null
                }
                sessionId?.let { pendingOps.flush(service, it) }
            }
            is AppShellResult.Failure -> {
                // RESTORING (retryable): bounded, backing-off, cancellable
                // retry. Success, unbind, destroy, or a changed session
                // target cancels further attempts.
                if (created.error.retryable && !attachCancelled) {
                    scheduleAttachRetry()
                }
            }
        }
    }

    private var attachRetryCount = 0
    internal var attachCancelled = false

    /** Test hook: the handler driving attach retries. */
    internal fun handlerForTest() = handler

    private fun scheduleAttachRetry() {
        if (attachRetryCount >= MAX_ATTACH_RETRIES || attachCancelled) return
        attachRetryCount += 1
        val delayMs = (ATTACH_RETRY_BASE_MS * attachRetryCount).coerceAtMost(ATTACH_RETRY_CAP_MS)
        handler.postDelayed(
            { if (!attachCancelled) attachSession() },
            delayMs,
        )
    }

    override fun onStart() {
        super.onStart()
        startRefresh()
    }

    override fun onStop() {
        stopRefresh()
        super.onStop()
    }

    override fun onDestroy() {
        stopRefresh()
        attachCancelled = true
        surface.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        // Cancel queued ops: destroy never replays them into a later session.
        pendingOps.clear()
        // Unbind only: the session outlives the Activity by contract.
        unbindService(connection)
        api = null
        super.onDestroy()
    }

    /** Idempotent: repeated starts never stack duplicate callbacks. */
    private fun startRefresh() {
        if (refreshActive) return
        refreshActive = true
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    /** Idempotent: safe when never started or already stopped. */
    private fun stopRefresh() {
        refreshActive = false
        handler.removeCallbacks(refresh)
    }

    /** Test hook: latest cached frame, decoded. No pump side effects — the
     *  service owner thread drives sessions. */
    internal fun cachedSnapshotForTest(): TerminalSnapshot? {
        val service = api ?: return null
        val id = sessionId ?: return null
        val frame = (service.cachedFrame(id) as? AppShellResult.Success)?.value ?: return null
        return TerminalSnapshotCodec.decode(frame.payload)
    }

    internal val childExitCode: Int?
        get() {
            val service = api ?: return null
            val id = sessionId ?: return null
            val snapshot = (service.session(id) as? AppShellResult.Success)?.value ?: return null
            return (snapshot.termination as? SessionTermination.ProcessExited)?.code
        }

    internal val attachedSessionId: Long? get() = sessionId?.value

    /** Derives terminal grid dimensions from the measured surface; null when
     *  the view has not been laid out with a non-zero size yet. */
    private fun measureTerminalSize(): Pair<Int, Int>? {
        val charWidth = surface.paint.measureText("M")
        val lineHeight = surface.lineHeight.toFloat()
        if (surface.width <= 0 || surface.height <= 0 || charWidth <= 0f || lineHeight <= 0f) {
            return null
        }
        val columns = (surface.width / charWidth).toInt().coerceAtLeast(1)
        val rows = (surface.height / lineHeight).toInt().coerceAtLeast(1)
        return columns to rows
    }

    private fun writeKey(keyCode: Int, event: KeyEvent): Boolean {
        val bytes = when {
            event.isCtrlPressed && event.unicodeChar == 'c'.code -> byteArrayOf(3)
            keyCode == KeyEvent.KEYCODE_ENTER -> "\r".encodeToByteArray()
            keyCode == KeyEvent.KEYCODE_DEL -> byteArrayOf(0x7f)
            keyCode == KeyEvent.KEYCODE_DPAD_UP -> "\u001b[A".encodeToByteArray()
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> "\u001b[B".encodeToByteArray()
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> "\u001b[C".encodeToByteArray()
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> "\u001b[D".encodeToByteArray()
            event.unicodeChar > 0 -> String(Character.toChars(event.unicodeChar)).encodeToByteArray()
            else -> return false
        }
        // Queue until bound; the op replays once against the attached session.
        dispatchOp { service, id ->
            service.writeInput(id, SessionInput.of(bytes))
        }
        return true
    }

    /** Runs [op] immediately when the service and session are attached,
     *  otherwise queues it for the flush at attach. */
    private fun dispatchOp(op: (TermuxServiceApi, SessionId) -> Unit) {
        val service = api
        val id = sessionId
        if (service != null && id != null) {
            op(service, id)
        } else {
            pendingOps.enqueue(sessionId, op)
        }
    }

    private companion object {
        const val TAG = "TerminalActivity"
        const val REFRESH_INTERVAL_MS = 16L
        const val KEY_SESSION_ID = "termux_session_id"
        const val MAX_ATTACH_RETRIES = 5
        const val ATTACH_RETRY_BASE_MS = 100L
        const val ATTACH_RETRY_CAP_MS = 1_000L
    }
}
