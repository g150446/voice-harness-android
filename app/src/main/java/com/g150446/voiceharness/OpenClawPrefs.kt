package com.g150446.voiceharness

import android.content.Context
import java.util.UUID

object OpenClawPrefs {
    private const val PREFS = "openclaw_prefs"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_TOKEN_CIPHER = "token_cipher"
    private const val KEY_SESSION_KEY = "session_key"
    const val DEFAULT_BASE_URL = "http://127.0.0.1:18789"

    fun getBaseUrl(context: Context): String = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_BASE_URL, DEFAULT_BASE_URL)
        ?.trim()
        ?.trimEnd('/')
        .orEmpty()
        .ifBlank { DEFAULT_BASE_URL }

    fun setBaseUrl(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BASE_URL, value.trim().trimEnd('/'))
            .apply()
    }

    fun getToken(context: Context): String {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN_CIPHER, "")
            .orEmpty()
        return SecureApiKeyStore.decrypt(context, stored).orEmpty()
    }

    fun setToken(context: Context, value: String) {
        val token = value.trim()
        val stored = if (token.isBlank()) "" else SecureApiKeyStore.encrypt(context, token)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TOKEN_CIPHER, stored)
            .apply()
    }

    fun getOrCreateSessionKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_SESSION_KEY, null)?.takeIf(String::isNotBlank)?.let { return it }
        val created = "voice-harness:${UUID.randomUUID()}"
        prefs.edit().putString(KEY_SESSION_KEY, created).apply()
        return created
    }
}
