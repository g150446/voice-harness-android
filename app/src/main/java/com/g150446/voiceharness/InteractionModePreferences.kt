package com.g150446.voiceharness

import android.content.Context

internal class InteractionModePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun mode(): InteractionMode {
        val raw = preferences.getString(KEY_MODE, InteractionMode.AI.name)
        return InteractionMode.entries.firstOrNull { it.name == raw } ?: InteractionMode.AI
    }

    fun setMode(mode: InteractionMode) {
        preferences.edit().putString(KEY_MODE, mode.name).apply()
    }

    private companion object {
        private const val PREFERENCES_NAME = "interaction_mode"
        private const val KEY_MODE = "mode"
    }
}
