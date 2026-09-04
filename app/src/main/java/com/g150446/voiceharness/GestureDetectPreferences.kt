package com.g150446.voiceharness

import android.content.Context

/**
 * Whether the node should run wrist-gesture start/stop.
 *
 * Default off: everyday wear is tap-only. The node keeps the flag in RAM only,
 * so the app re-asserts it after every connect or node reset.
 */
internal class GestureDetectPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun enabled(): Boolean = preferences.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private companion object {
        private const val PREFERENCES_NAME = "gesture_detect"
        private const val KEY_ENABLED = "enabled"
    }
}
