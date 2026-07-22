package com.termux.rust

import android.app.NotificationManager
import android.content.Context

/**
 * Notification-visibility boundary for API 24+. `areNotificationsEnabled`
 * reports the **app-level** notification visibility toggle (present since
 * API 24): on 33+ a POST_NOTIFICATIONS denial maps to false, which is what
 * this gate consumes. It is NOT identical to the API 33 runtime permission
 * and does NOT cover channel-level blocks (our single LOW channel makes
 * that residual risk small).
 */
internal interface NotificationPermissionGate {
    fun areNotificationsEnabled(context: Context): Boolean

    object System : NotificationPermissionGate {
        override fun areNotificationsEnabled(context: Context): Boolean =
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    }
}
