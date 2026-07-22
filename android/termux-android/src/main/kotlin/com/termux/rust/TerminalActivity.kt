package com.termux.rust

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.ViewTreeObserver
import android.widget.TextView

class TerminalActivity : Activity() {
    private lateinit var supervisor: TerminalSessionSupervisor
    private lateinit var surface: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var lastRenderedVersion = -1L
    private var refreshActive = false
    private var exitAnnounced = false

    internal var terminalColumns = 80
        private set
    internal var terminalRows = 24
        private set

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        val measured = measureTerminalSize()
        if (measured != null && (measured.first != terminalColumns || measured.second != terminalRows)) {
            terminalColumns = measured.first
            terminalRows = measured.second
            if (::supervisor.isInitialized) {
                runCatching { supervisor.resize(terminalColumns, terminalRows) }
                    .onFailure { Log.w(TAG, "resize failed; session may have exited", it) }
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

    private fun refreshOnce() {
        if (!::supervisor.isInitialized) return
        val rendered = supervisor.pumpFrame() ?: return
        val snapshot = TerminalSnapshotCodec.decode(rendered)
        if (snapshot.version != lastRenderedVersion) {
            lastRenderedVersion = snapshot.version
            surface.text = TerminalTextRenderer.render(snapshot)
        }
        announceExitIfNeeded()
    }

    private fun announceExitIfNeeded() {
        val code = supervisor.exitCode
        if (code != null && !exitAnnounced) {
            exitAnnounced = true
            surface.append("\n[process exited with code $code]")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        supervisor = TerminalSessionSupervisor("/system/bin/sh", emptyList(), terminalColumns, terminalRows)
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
        surface.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        if (::supervisor.isInitialized) {
            supervisor.close()
        }
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

    /** Test hook: pumps one frame (side effect: advances output and exit
     *  polling) and returns the decoded snapshot. Not for production paths. */
    internal fun pumpAndRenderSnapshotForTest(): TerminalSnapshot? =
        supervisor.pumpFrame()?.let(TerminalSnapshotCodec::decode)

    internal val childExitCode: Int? get() = if (::supervisor.isInitialized) supervisor.exitCode else null

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
        supervisor.writeInput(bytes)
        return true
    }

    private companion object {
        const val TAG = "TerminalActivity"
        const val REFRESH_INTERVAL_MS = 16L
    }
}
