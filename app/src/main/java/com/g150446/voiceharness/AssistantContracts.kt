package com.g150446.voiceharness

/** Origin of a request entering the shared assistant runtime. */
enum class QueryOrigin {
    HARNESS_NODE_VOICE,
    DIGITAL_ASSISTANT_VOICE,
    DIGITAL_ASSISTANT_TEXT,
}

/** Transient screen snapshot for a single ChatRequest. Never stored in conversation history. */
data class ScreenContext(
    val assistText: String? = null,
    val sourcePackage: String? = null,
    val sourceUri: String? = null,
    val jpegBytes: ByteArray? = null,
    val capturedAt: Long = System.currentTimeMillis(),
) {
    val hasText: Boolean get() = !assistText.isNullOrBlank()
    val hasImage: Boolean get() = jpegBytes != null && jpegBytes.isNotEmpty()
    val isEmpty: Boolean get() = !hasText && !hasImage

    fun withoutImage(): ScreenContext = copy(jpegBytes = null)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScreenContext) return false
        return assistText == other.assistText &&
            sourcePackage == other.sourcePackage &&
            sourceUri == other.sourceUri &&
            capturedAt == other.capturedAt &&
            jpegBytes.contentEquals(other.jpegBytes)
    }

    override fun hashCode(): Int {
        var result = assistText?.hashCode() ?: 0
        result = 31 * result + (sourcePackage?.hashCode() ?: 0)
        result = 31 * result + (sourceUri?.hashCode() ?: 0)
        result = 31 * result + capturedAt.hashCode()
        result = 31 * result + (jpegBytes?.contentHashCode() ?: 0)
        return result
    }
}

data class ChatRequest(
    val conversationHistory: List<ConversationTurn>,
    val languageCode: String? = null,
    val screenContext: ScreenContext? = null,
)

data class AssistantRequest(
    val text: String,
    val origin: QueryOrigin,
    val requestId: String? = null,
    val conversationId: String? = null,
    val speakResponse: Boolean = true,
    val screenContext: ScreenContext? = null,
    val languageCode: String? = null,
)

data class AssistantResult(
    val text: String,
    val conversationId: String,
    val requestId: String? = null,
    val toolCalls: List<ChatToolCall> = emptyList(),
    val latencyMs: Long = 0L,
)

/** Stable boundary shared by BLE and Android's digital-assistant entry points. */
interface AssistantGateway {
    suspend fun submit(request: AssistantRequest): Result<AssistantResult>
    fun resetConversation(conversationId: String)
}
