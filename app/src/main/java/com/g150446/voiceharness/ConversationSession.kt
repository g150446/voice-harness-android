package com.g150446.voiceharness

import android.util.Log

private const val TAG = "ConversationSession"
private const val SESSION_TIMEOUT_MS = 10 * 60 * 1000L // 10 minutes
private const val MAX_INFERENCE_TURNS = 3

internal fun limitConversationTurnsForInference(
    turns: List<ConversationTurn>
): List<ConversationTurn> = turns.takeLast(MAX_INFERENCE_TURNS)

/**
 * Manages a multi-turn conversation session.
 * Automatically expires after 10 minutes of inactivity or when explicitly reset.
 */
class ConversationSession {

    private val _turns = mutableListOf<ConversationTurn>()
    private var lastActivityAt = System.currentTimeMillis()

    val turns: List<ConversationTurn> get() = _turns.toList()

    /**
     * Returns the current user turn plus at most one preceding user/assistant exchange.
     * Full history remains available for persistence/UI without repeatedly prefilling the LLM.
     */
    fun turnsForInference(): List<ConversationTurn> {
        return limitConversationTurnsForInference(_turns)
    }

    fun isActive(): Boolean {
        return _turns.isNotEmpty() && !isExpired()
    }

    fun isExpired(): Boolean {
        return System.currentTimeMillis() - lastActivityAt > SESSION_TIMEOUT_MS
    }

    fun addTurn(role: String, content: String) {
        _turns.add(ConversationTurn(role = role, content = content))
        lastActivityAt = System.currentTimeMillis()
        Log.d(TAG, "Added turn: role=$role, turns=${_turns.size}")
    }

    fun reset() {
        if (_turns.isNotEmpty()) {
            Log.d(TAG, "Resetting conversation session (had ${_turns.size} turns)")
        }
        _turns.clear()
        lastActivityAt = 0L
    }

    fun touch() {
        lastActivityAt = System.currentTimeMillis()
    }
}
