package com.g150446.voiceharness

import android.content.Context

object OpenRouterPrefs {
    private const val PREFS = "openrouter_prefs"
    private const val KEY_API_KEY_CIPHER = "api_key_cipher"
    private const val KEY_MODEL_ID = "model_id"
    private const val KEY_MODELS_CACHE_JSON = "models_cache_json"
    private const val KEY_MODELS_CACHE_AT = "models_cache_at"

    fun getApiKey(context: Context): String {
        val cipher = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_API_KEY_CIPHER, "")
            .orEmpty()
        if (cipher.isBlank()) return ""
        return SecureApiKeyStore.decrypt(context, cipher)
            ?: "" // undecryptable → treat as missing; UI will ask to re-enter
    }

    fun setApiKey(context: Context, apiKey: String) {
        val trimmed = apiKey.trim()
        val stored = if (trimmed.isEmpty()) {
            ""
        } else {
            SecureApiKeyStore.encrypt(context, trimmed)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_API_KEY_CIPHER, stored)
            .apply()
    }

    fun hasApiKey(context: Context): Boolean = getApiKey(context).isNotBlank()

    fun getModelId(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODEL_ID, "")
            ?.trim()
            .orEmpty()

    fun setModelId(context: Context, modelId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODEL_ID, modelId.trim())
            .apply()
    }

    fun getModelsCacheJson(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODELS_CACHE_JSON, null)

    fun getModelsCacheAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_MODELS_CACHE_AT, 0L)

    fun setModelsCache(context: Context, json: String, cachedAt: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODELS_CACHE_JSON, json)
            .putLong(KEY_MODELS_CACHE_AT, cachedAt)
            .apply()
    }
}
