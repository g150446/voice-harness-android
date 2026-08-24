package com.g150446.voiceharness

/** Origin of a request entering the shared assistant runtime. */
enum class QueryOrigin {
    HARNESS_NODE_VOICE,
    DIGITAL_ASSISTANT_VOICE,
    DIGITAL_ASSISTANT_TEXT,
}

data class AssistantRequest(
    val text: String,
    val origin: QueryOrigin,
    val conversationId: String? = null,
    val speakResponse: Boolean = true,
    val screenContext: String? = null,
    val languageCode: String? = null,
)

data class AssistantResult(
    val text: String,
    val conversationId: String,
    val toolCalls: List<ChatToolCall> = emptyList(),
    val latencyMs: Long = 0L,
)

/** Stable boundary shared by BLE and Android's digital-assistant entry points. */
interface AssistantGateway {
    suspend fun submit(request: AssistantRequest): Result<AssistantResult>
    fun resetConversation(conversationId: String)
}
