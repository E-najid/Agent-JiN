package com.ngi.agentjin.core.storage

import android.content.Context
import android.net.Uri

/**
 * Device-local preferences (not the portable SD-card folder).
 * Tree URI, lockout, download settings. Never stores the master password.
 */
class DevicePreferences(context: Context) {
    private val prefs = devicePrefs(context, "agentjin_device_prefs")

    var treeUri: Uri?
        get() = prefs.getString(KEY_TREE_URI, null)?.let(Uri::parse)
        set(value) {
            prefs.edit().putString(KEY_TREE_URI, value?.toString()).apply()
        }

    var wifiOnlyDownloads: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, true)
        set(value) {
            prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()
        }

    var maxStepsPerTask: Int
        get() = prefs.getInt(KEY_MAX_STEPS, 12)
        set(value) {
            prefs.edit().putInt(KEY_MAX_STEPS, value.coerceIn(1, 64)).apply()
        }

    var confirmScreenActions: Boolean
        get() = prefs.getBoolean(KEY_CONFIRM_SCREEN, true)
        set(value) {
            prefs.edit().putBoolean(KEY_CONFIRM_SCREEN, value).apply()
        }

    var biometricUnlockEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC, false)
        set(value) {
            prefs.edit().putBoolean(KEY_BIOMETRIC, value).apply()
        }

    var failedAttempts: Int
        get() = prefs.getInt(KEY_FAILED, 0)
        set(value) {
            prefs.edit().putInt(KEY_FAILED, value).apply()
        }

    var lockUntilEpochMs: Long
        get() = prefs.getLong(KEY_LOCK_UNTIL, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LOCK_UNTIL, value).apply()
        }

    var setupCompleted: Boolean
        get() = prefs.getBoolean(KEY_SETUP_DONE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SETUP_DONE, value).apply()
        }

    fun recordFailedAttempt(now: Long = System.currentTimeMillis()): Long {
        val n = failedAttempts + 1
        failedAttempts = n
        val backoffMs = backoffFor(n)
        val until = if (backoffMs > 0) now + backoffMs else 0L
        lockUntilEpochMs = until
        return until
    }

    fun clearFailedAttempts() {
        failedAttempts = 0
        lockUntilEpochMs = 0L
    }

    fun remainingLockMs(now: Long = System.currentTimeMillis()): Long {
        return (lockUntilEpochMs - now).coerceAtLeast(0L)
    }

    companion object {
        private const val KEY_TREE_URI = "tree_uri"
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_MAX_STEPS = "max_steps"
        private const val KEY_CONFIRM_SCREEN = "confirm_screen"
        private const val KEY_BIOMETRIC = "biometric"
        private const val KEY_FAILED = "failed_attempts"
        private const val KEY_LOCK_UNTIL = "lock_until"
        private const val KEY_SETUP_DONE = "setup_done"

        fun backoffFor(attempt: Int): Long {
            // First 5 tries free, then 30s, 1m, 5m, 15m, 1h (capped).
            if (attempt <= 5) return 0L
            val idx = (attempt - 6).coerceAtMost(4)
            val table = longArrayOf(30_000L, 60_000L, 300_000L, 900_000L, 3_600_000L)
            return table[idx]
        }
    }
}
