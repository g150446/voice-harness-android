package com.g150446.voiceharness

/**
 * A single turn in a conversation with the LLM.
 */
data class ConversationTurn(
    val role: String, // "user" or "assistant" or "system"
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)
