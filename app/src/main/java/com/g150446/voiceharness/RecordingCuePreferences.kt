package com.g150446.voiceharness

import android.content.Context

/** Start/stop beep for BLE recording. Default off to avoid unexpected audio. */
internal class RecordingCuePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun enabled(): Boolean = preferences.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private companion object {
        private const val PREFERENCES_NAME = "recording_cue"
        private const val KEY_ENABLED = "enabled"
    }
}
