package com.g150446.voiceharness

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterChatRequestBuilderTest {
    @Test
    fun `text before image in multimodal content`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val body = OpenRouterChatRequestBuilder.buildRequestBody(
            modelId = "openai/gpt-4o",
            conversationHistory = listOf(ConversationTurn("user", "what is this?")),
            languageCode = "ja",
            screenContext = ScreenContext(assistText = "label", jpegBytes = jpeg),
            supportsTools = true,
            supportsImage = true,
        )
        val root = JSONObject(body)
        val messages = root.getJSONArray("messages")
        val last = messages.getJSONObject(messages.length() - 1)
        val content = last.getJSONArray("content")
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("image_url", content.getJSONObject(1).getString("type"))
        assertTrue(root.has("tools"))
    }

    @Test
    fun `omits tools when unsupported`() {
        val body = OpenRouterChatRequestBuilder.buildRequestBody(
            modelId = "x/y",
            conversationHistory = listOf(ConversationTurn("user", "hi")),
            languageCode = null,
            screenContext = null,
            supportsTools = false,
            supportsImage = false,
        )
        val root = JSONObject(body)
        assertFalse(root.has("tools"))
    }

    @Test
    fun `safe http error redacts key-like tokens`() {
        val msg = OpenRouterChatRequestBuilder.safeHttpError(
            401,
            """{"error":"bad sk-abc123XYZ key"}""",
        )
        assertTrue(msg.contains("401"))
        assertFalse(msg.contains("sk-abc123XYZ"))
    }

    @Test
    fun `parses tool calls`() {
        val json = """
            {"choices":[{"message":{"content":"ok","tool_calls":[
              {"function":{"name":"set_reminder","arguments":"{\"title\":\"a\"}"}}
            ]}}]}
        """.trimIndent()
        val result = OpenRouterChatRequestBuilder.parseChatResponse(json)
        assertEquals("ok", result.text)
        assertEquals(1, result.toolCalls.size)
        assertEquals("set_reminder", result.toolCalls[0].name)
    }
}
