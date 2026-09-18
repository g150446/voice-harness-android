package com.g150446.voiceharness

import android.content.Context

internal class HarborFontSizePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun size(): Int = preferences.getInt(KEY_SIZE, DEFAULT_SIZE).coerceIn(MIN_SIZE, MAX_SIZE)

    fun setSize(size: Int) {
        preferences.edit()
            .putInt(KEY_SIZE, size.coerceIn(MIN_SIZE, MAX_SIZE))
            .apply()
    }

    internal companion object {
        const val MIN_SIZE = 8
        const val MAX_SIZE = 24
        const val DEFAULT_SIZE = 12
        private const val PREFERENCES_NAME = "harbor_font_size"
        private const val KEY_SIZE = "size"
    }
}
