package com.termux.rust

/**
 * Pure foreground-notification and drive-frequency policy, driven by the
 * registry summary after each drive pass. Android-side effects
 * (startForeground/stopForeground/notification updates) are applied by
 * [TermuxService]; decisions are idempotent — the same summary repeated
 * yields the same decision, so callers never oscillate.
 */
internal object ForegroundPolicy {
    const val ACTIVE_INTERVAL_MS = 16L
    const val IDLE_INTERVAL_MS = 500L

    data class Decision(
        val foregroundRequired: Boolean,
        val driveIntervalMs: Long,
        val sessionCount: Int,
    )

    fun decide(
        summary: RegistrySummary,
        sessionCount: Int,
    ): Decision = Decision(
        foregroundRequired = summary.requiresForeground,
        // Poll rate follows live work only: a registry holding nothing but
        // CLOSED entries idles instead of spinning.
        driveIntervalMs = if (summary.requiresForeground) ACTIVE_INTERVAL_MS else IDLE_INTERVAL_MS,
        sessionCount = sessionCount,
    )
}
