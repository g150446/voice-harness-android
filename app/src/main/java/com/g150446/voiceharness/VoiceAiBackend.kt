package com.g150446.voiceharness

import java.io.File

data class TranscriptionResult(
    val text: String,
    val languageCode: String? = null,
    val latencyMs: Long = 0L
)

data class ChatToolCall(
    val name: String,
    val argumentsJson: String
)

data class ChatResult(
    val text: String,
    val toolCalls: List<ChatToolCall> = emptyList(),
    val latencyMs: Long = 0L
)

interface SttBackend {
    val name: String
    suspend fun ensureReady(): Result<Unit>
    suspend fun transcribe(audioFile: File): Result<TranscriptionResult>
    fun release()
}

interface LlmBackend {
    val name: String
    suspend fun ensureReady(): Result<Unit>
    suspend fun chat(
        conversationHistory: List<ConversationTurn>,
        languageCode: String?
    ): Result<ChatResult>
    fun release()
}

/**
 * Combined backend used by VoiceProcessor. Profile switches STT/LLM pair.
 */
interface VoiceAiBackend {
    val name: String
    val profile: OnDeviceProfile
    suspend fun ensureReady(): Result<Unit>
    suspend fun transcribe(audioFile: File): Result<TranscriptionResult>
    suspend fun chat(
        conversationHistory: List<ConversationTurn>,
        languageCode: String?
    ): Result<ChatResult>
    fun release()
}
