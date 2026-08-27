package com.g150446.voiceharness

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendAssistantGatewayTest {
    @Test
    fun `keeps independent conversation histories by id`() = runBlocking {
        val backend = RecordingBackend()
        val gateway = BackendAssistantGateway(backend)

        gateway.submit(request("first", "a")).getOrThrow()
        gateway.submit(request("second", "a")).getOrThrow()
        gateway.submit(request("other", "b")).getOrThrow()

        assertEquals(listOf("user:first"), backend.histories[0])
        assertEquals(listOf("user:first", "assistant:reply-1", "user:second"), backend.histories[1])
        assertEquals(listOf("user:other"), backend.histories[2])
    }

    @Test
    fun `reset drops prior conversation context`() = runBlocking {
        val backend = RecordingBackend()
        val gateway = BackendAssistantGateway(backend)
        gateway.submit(request("before", "session")).getOrThrow()

        gateway.resetConversation("session")
        val result = gateway.submit(request("after", "session")).getOrThrow()

        assertEquals(listOf("user:after"), backend.histories.last())
        assertEquals("session", result.conversationId)
    }

    @Test
    fun `blank query fails before backend invocation`() = runBlocking {
        val backend = RecordingBackend()
        val gateway = BackendAssistantGateway(backend)

        assertTrue(gateway.submit(request("  ", "session")).isFailure)
        assertTrue(backend.histories.isEmpty())
    }

    @Test
    fun `harness node origin passes screen context`() = runBlocking {
        val backend = RecordingBackend()
        val gateway = BackendAssistantGateway(backend)
        gateway.submit(
            AssistantRequest(
                text = "look",
                origin = QueryOrigin.HARNESS_NODE_VOICE,
                conversationId = "h",
                screenContext = ScreenContext(assistText = "visible-from-node"),
            )
        ).getOrThrow()
        assertEquals("visible-from-node", backend.screenContexts[0]?.assistText)
    }

    @Test
    fun `empty screen context is stripped`() = runBlocking {
        val backend = RecordingBackend()
        val gateway = BackendAssistantGateway(backend)
        gateway.submit(
            AssistantRequest(
                text = "look",
                origin = QueryOrigin.HARNESS_NODE_VOICE,
                conversationId = "h",
                screenContext = ScreenContext(),
            )
        ).getOrThrow()
        assertNull(backend.screenContexts[0])
    }

    @Test
    fun `digital assistant passes screen context`() = runBlocking {
        val backend = RecordingBackend()
        val gateway = BackendAssistantGateway(backend)
        val screen = ScreenContext(assistText = "visible")
        gateway.submit(
            AssistantRequest(
                text = "look",
                origin = QueryOrigin.DIGITAL_ASSISTANT_TEXT,
                conversationId = "d",
                screenContext = screen,
            )
        ).getOrThrow()
        assertEquals("visible", backend.screenContexts[0]?.assistText)
    }

    private fun request(text: String, id: String) = AssistantRequest(
        text = text,
        origin = QueryOrigin.HARNESS_NODE_VOICE,
        conversationId = id,
    )

    private class RecordingBackend : VoiceAiBackend {
        val histories = mutableListOf<List<String>>()
        val screenContexts = mutableListOf<ScreenContext?>()
        override val name = "fake"
        override val profile = OnDeviceProfile.GEMMA
        override suspend fun ensureReady() = Result.success(Unit)
        override suspend fun transcribe(audioFile: File, vocabulary: List<AsrVocabularyTerm>) =
            Result.success(TranscriptionResult("unused"))

        override suspend fun chat(request: ChatRequest): Result<ChatResult> {
            histories += request.conversationHistory.map { "${it.role}:${it.content}" }
            screenContexts += request.screenContext
            return Result.success(ChatResult("reply-${histories.size}"))
        }

        override fun release() = Unit
    }
}
