package com.g150446.voiceharness

import android.content.Context

/**
 * Whether the node should dump the 6-axis trajectory for every gesture attempt.
 *
 * Persisted because the switch is meant to stay on for a whole day of data
 * collection, and because the node keeps its own copy only in RAM — the app is
 * the one that has to re-assert it after a node reset or reconnect.
 */
internal class GestureCapturePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun enabled(): Boolean = preferences.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private companion object {
        private const val PREFERENCES_NAME = "gesture_capture"
        private const val KEY_ENABLED = "enabled"
    }
}
