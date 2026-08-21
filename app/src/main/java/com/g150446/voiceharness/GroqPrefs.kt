package com.g150446.voiceharness

import android.content.Context

object GroqPrefs {
    private const val PREFS = "groq_prefs"
    private const val KEY_API_KEY = "groq_api_key"

    fun getApiKey(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_API_KEY, "")
            ?.trim()
            .orEmpty()

    fun setApiKey(context: Context, apiKey: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_API_KEY, apiKey.trim())
            .apply()
    }

    fun hasApiKey(context: Context): Boolean = getApiKey(context).isNotBlank()
}
