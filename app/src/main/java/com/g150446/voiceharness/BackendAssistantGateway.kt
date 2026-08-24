package com.g150446.voiceharness

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Shared, headless assistant coordinator used by both harness-node and the
 * system digital-assistant surface. UI components never own conversation state.
 */
internal class BackendAssistantGateway(
    private val backend: VoiceAiBackend,
) : AssistantGateway {
    private val sessions = ConcurrentHashMap<String, ConversationSession>()
    private val backendMutex = Mutex()

    override suspend fun submit(request: AssistantRequest): Result<AssistantResult> =
        backendMutex.withLock {
            runCatching {
                val query = request.text.trim()
                require(query.isNotEmpty()) { "Assistant query must not be blank" }

                val conversationId = request.conversationId ?: UUID.randomUUID().toString()
                val session = sessions.getOrPut(conversationId) { ConversationSession() }
                if (session.isExpired()) session.reset()

                session.addTurn("user", query)
                val result = backend.chat(
                    conversationHistory = session.turnsForInference(),
                    languageCode = request.languageCode,
                ).getOrThrow()
                if (result.text.isNotBlank()) session.addTurn("assistant", result.text)

                AssistantResult(
                    text = result.text,
                    conversationId = conversationId,
                    toolCalls = result.toolCalls,
                    latencyMs = result.latencyMs,
                )
            }
        }

    override fun resetConversation(conversationId: String) {
        sessions.remove(conversationId)?.reset()
    }
}
