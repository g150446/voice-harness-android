package com.g150446.voiceharness

import android.content.Context

/** Tap event that starts and stops BLE recording. */
enum class RecordingTapMode {
    SINGLE,
    DOUBLE;

    companion object {
        fun fromStorage(value: String?): RecordingTapMode =
            entries.firstOrNull { it.name == value } ?: SINGLE
    }
}

/** Persists the recording tap mode. Existing installs default to single tap. */
internal class RecordingTapPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun mode(): RecordingTapMode = RecordingTapMode.fromStorage(
        preferences.getString(KEY_MODE, null),
    )

    fun setMode(mode: RecordingTapMode) {
        preferences.edit().putString(KEY_MODE, mode.name).apply()
    }

    private companion object {
        private const val PREFERENCES_NAME = "recording_tap"
        private const val KEY_MODE = "mode"
    }
}
