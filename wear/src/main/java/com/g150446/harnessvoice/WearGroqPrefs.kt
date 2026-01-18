package com.g150446.harnessvoice

import android.content.Context
import android.content.SharedPreferences

object WearGroqPrefs {
    private const val PREFS_NAME = "groq_prefs"
    private const val KEY_API = "groq_api_key"
    private const val KEY_GESTURE_MODE = "gesture_mode"

    fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveApiKey(context: Context, apiKey: String) {
        getPrefs(context).edit().putString(KEY_API, apiKey).apply()
    }

    fun getApiKey(context: Context): String? = getPrefs(context).getString(KEY_API, null)

    fun saveGestureMode(context: Context, gestureMode: String) {
        getPrefs(context).edit().putString(KEY_GESTURE_MODE, gestureMode).apply()
    }

    fun getGestureMode(context: Context): String? = getPrefs(context).getString(KEY_GESTURE_MODE, null)
}


