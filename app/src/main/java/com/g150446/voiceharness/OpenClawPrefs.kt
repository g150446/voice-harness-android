package com.g150446.voiceharness

import android.content.Context
import java.util.UUID

object OpenClawPrefs {
    private const val PREFS = "openclaw_prefs"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_TOKEN_CIPHER = "token_cipher"
    private const val KEY_SESSION_KEY = "session_key"
    private const val KEY_SELECTED_SESSION_KEY = "selected_session_key"
    private const val KEY_AGENT = "agent"
    private const val KEY_HARBOR_AGENT = "harbor_agent"
    const val DEFAULT_BASE_URL = "http://127.0.0.1:18789"
    const val DEFAULT_AGENT = "openclaw/default"
    const val DEFAULT_HARBOR_AGENT = "openclaw/default"

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

    /**
     * Agent target for ordinary chat. Only has to be a named agent (`openclaw/main`) on a
     * Gateway that runs several: such a fleet has no default, and refuses any request whose
     * session it cannot attribute to one agent.
     */
    fun getAgent(context: Context): String = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_AGENT, DEFAULT_AGENT)
        ?.trim()
        .orEmpty()
        .ifBlank { DEFAULT_AGENT }

    fun setAgent(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_AGENT, value.trim())
            .apply()
    }

    /**
     * Agent target that interprets Harbor voice. An agent whose config denies the Gateway's
     * own `harbor_send_*` tools cannot bypass the phone's tap confirmation, so a dedicated
     * one (`openclaw/harbor-voice`) is worth setting up; the default agent also works.
     */
    fun getHarborAgent(context: Context): String = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_HARBOR_AGENT, DEFAULT_HARBOR_AGENT)
        ?.trim()
        .orEmpty()
        .ifBlank { DEFAULT_HARBOR_AGENT }

    fun setHarborAgent(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HARBOR_AGENT, value.trim())
            .apply()
    }

    fun getOrCreateSessionKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_SESSION_KEY, null)?.takeIf(String::isNotBlank)?.let { return it }
        val created = "voice-harness:${UUID.randomUUID()}"
        prefs.edit().putString(KEY_SESSION_KEY, created).apply()
        return created
    }

    /** Existing Gateway session (e.g. the one open in the browser) chosen in settings; null = app-owned session. */
    fun getSelectedSessionKey(context: Context): String? = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_SELECTED_SESSION_KEY, null)
        ?.trim()
        ?.takeIf(String::isNotBlank)

    fun setSelectedSessionKey(context: Context, key: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .apply {
                if (key.isNullOrBlank()) remove(KEY_SELECTED_SESSION_KEY)
                else putString(KEY_SELECTED_SESSION_KEY, key.trim())
            }
            .apply()
    }

    /** Session used for chat and history: the selected Gateway session, else the app-owned one. */
    fun getChatSessionKey(context: Context): String =
        getSelectedSessionKey(context) ?: getOrCreateSessionKey(context)

    /**
     * Session that interprets voice for one Harbor workspace.
     *
     * Always derived from the app-owned key, never from a Gateway session the user picked in
     * settings: hanging terminal control off someone's browser conversation would mix two
     * different threads and move with their choice of session.
     */
    fun harborSessionKey(context: Context, workspaceId: String?): String =
        harborSessionKey(getOrCreateSessionKey(context), workspaceId)
}

/**
 * `<app session>:harbor:<workspace>`. One session per workspace, so 「さっきの続き」 means the
 * terminal the user is looking at and nothing carries over from another one.
 */
internal fun harborSessionKey(base: String, workspaceId: String?): String {
    val scope = sanitizeSessionSegment(workspaceId)
    return if (scope.isEmpty()) "$base:harbor" else "$base:harbor:$scope"
}

internal fun isHarborSessionKey(key: String): Boolean =
    key.endsWith(":harbor") || key.contains(":harbor:")

/**
 * The agent an `openclaw/<id>` target names, or null for the fleet-less `openclaw` and
 * `openclaw/default`, which do not name one.
 */
internal fun openClawAgentId(model: String?): String? {
    val target = model?.trim()?.removePrefix("openclaw/")?.trim().orEmpty()
    if (target.isEmpty() || target == "openclaw" || target == "default") return null
    return target
}

/**
 * Prefixes a session key with the agent that owns it.
 *
 * A Gateway running several agents has no default one, and rejects a request whose session it
 * cannot attribute — so once the target names an agent, the key has to say so too. A Gateway
 * with a single agent needs none of this, which is why an unnamed target is left alone.
 */
internal fun agentScopedSessionKey(model: String?, key: String): String {
    val agentId = openClawAgentId(model) ?: return key
    if (key.startsWith("agent:")) return key
    return "agent:$agentId:$key"
}

/** Colons would create a namespace of their own, and the Gateway reserves several. */
private fun sanitizeSessionSegment(value: String?): String =
    value?.trim().orEmpty().map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }
        .joinToString("")
        .trim('-')
        .take(64)

/**
 * OpenClaw is a destination, not an LLM: while the OpenClaw interaction mode is on, every request
 * (Node voice, in-app mic, in-app text) goes to the Gateway instead of the selected LLM.
 */
internal fun isOpenClawRoute(): Boolean =
    BleConnectionService.interactionMode.value == InteractionMode.OPENCLAW
