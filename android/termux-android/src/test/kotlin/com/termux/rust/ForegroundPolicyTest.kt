package com.termux.rust

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForegroundPolicyTest {
    @Test
    fun first_live_session_requires_foreground() {
        val decision = ForegroundPolicy.decide(RegistrySummary(running = 1), sessionCount = 1)
        assertTrue(decision.foregroundRequired)
        assertEquals(ForegroundPolicy.ACTIVE_INTERVAL_MS, decision.driveIntervalMs)
    }

    @Test
    fun starting_and_cancelling_also_require_foreground() {
        assertTrue(ForegroundPolicy.decide(RegistrySummary(starting = 1), 1).foregroundRequired)
        assertTrue(ForegroundPolicy.decide(RegistrySummary(cancelling = 1, exited = 2), 3).foregroundRequired)
    }

    @Test
    fun terminal_only_sessions_drop_foreground_and_idle() {
        // A registry holding only EXITED/CLOSED entries has no live work:
        // foreground drops and the poll rate falls to idle regardless of the
        // retained session count.
        val decision = ForegroundPolicy.decide(RegistrySummary(exited = 2), sessionCount = 2)
        assertFalse(decision.foregroundRequired)
        assertEquals(ForegroundPolicy.IDLE_INTERVAL_MS, decision.driveIntervalMs)
    }

    @Test
    fun empty_registry_idles_at_low_frequency() {
        val decision = ForegroundPolicy.decide(RegistrySummary(), sessionCount = 0)
        assertFalse(decision.foregroundRequired)
        assertEquals(ForegroundPolicy.IDLE_INTERVAL_MS, decision.driveIntervalMs)
    }

    @Test
    fun decisions_are_idempotent_for_repeated_summaries() {
        val summary = RegistrySummary(running = 2)
        assertEquals(
            ForegroundPolicy.decide(summary, 2),
            ForegroundPolicy.decide(summary, 2),
        )
    }
}
