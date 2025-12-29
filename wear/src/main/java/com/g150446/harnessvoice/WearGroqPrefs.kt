package com.g150446.harnessvoice

import android.content.Context
import android.content.SharedPreferences

object WearGroqPrefs {
    private const val PREFS_NAME = "groq_prefs"
    private const val KEY_API = "groq_api_key"

    fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveApiKey(context: Context, apiKey: String) {
        getPrefs(context).edit().putString(KEY_API, apiKey).apply()
    }

    fun getApiKey(context: Context): String? = getPrefs(context).getString(KEY_API, null)
}


