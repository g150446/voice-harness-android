package com.g150446.voiceharness

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenClawChatRequestBuilderTest {
    @Test
    fun `request targets default agent and sends only latest user turn`() {
        val body = JSONObject(
            OpenClawChatRequestBuilder.buildRequestBody(
                ChatRequest(
                    conversationHistory = listOf(
                        ConversationTurn("user", "old question"),
                        ConversationTurn("assistant", "old answer"),
                        ConversationTurn("user", "new question"),
                    ),
                    languageCode = "ja",
                ),
            ),
        )

        assertEquals("openclaw/default", body.getString("model"))
        val messages = body.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("new question", messages.getJSONObject(1).getString("content"))
        assertFalse(body.toString().contains("old question"))
    }

    @Test
    fun `harbor request pins harbor tool without changing model target`() {
        val body = JSONObject(
            OpenClawChatRequestBuilder.buildRequestBody(
                ChatRequest(
                    conversationHistory = listOf(ConversationTurn("user", "run tests")),
                    harborToolEnabled = true,
                    forceHarborCommand = true,
                ),
            ),
        )

        assertEquals("openclaw/default", body.getString("model"))
        assertTrue(body.getJSONArray("tools").length() > 0)
        assertEquals(
            HARBOR_COMMAND_TOOL_NAME,
            body.getJSONObject("tool_choice").getJSONObject("function").getString("name"),
        )
    }

    @Test
    fun `errors are mapped without reflecting gateway response`() {
        val secret = "super-secret-gateway-token"
        val error = OpenClawChatRequestBuilder.safeHttpError(
            401,
            """{"error":{"message":"bad $secret"}}""",
        )

        assertTrue(error.contains("認証"))
        assertFalse(error.contains(secret))
        assertTrue(OpenClawChatRequestBuilder.safeHttpError(404, "").contains("有効化"))
        assertTrue(OpenClawChatRequestBuilder.safeHttpError(429, "").contains("要求上限"))
    }
}
