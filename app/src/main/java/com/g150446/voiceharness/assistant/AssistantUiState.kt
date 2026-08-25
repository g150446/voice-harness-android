package com.g150446.voiceharness.assistant

enum class AssistantPhase {
    IDLE,
    LISTENING,
    RECOGNIZING,
    GENERATING,
    SPEAKING,
    ERROR,
}

data class AssistantChatMessage(
    val id: String,
    val role: String,
    val content: String,
)

data class AssistantUiState(
    val sessionActive: Boolean = false,
    val conversationId: String = "",
    val sourcePackage: String? = null,
    val sourceLabel: String? = null,
    val screenAvailable: Boolean = false,
    val useScreenContext: Boolean = true,
    val messages: List<AssistantChatMessage> = emptyList(),
    val draftText: String = "",
    val phase: AssistantPhase = AssistantPhase.IDLE,
    val statusText: String = "",
    val errorMessage: String? = null,
    val locked: Boolean = false,
)
