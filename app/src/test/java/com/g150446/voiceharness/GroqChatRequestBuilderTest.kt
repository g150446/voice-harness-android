package com.g150446.voiceharness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroqChatRequestBuilderTest {

    @Test
    fun buildMessageSpecs_addsSameLanguageSystemPromptWhenLanguageKnown() {
        val messages = GroqChatRequestBuilder.buildMessageSpecs(
            userText = "Hello there",
            languageCode = "en"
        )

        assertEquals(2, messages.size)
        assertEquals("system", messages[0].role)
        assertTrue(messages[0].content.contains("English (en)"))
        assertTrue(messages[0].content.contains("Do not translate unless the user explicitly asks for translation."))
        assertTrue(messages[0].content.contains("Keep responses brief unless the user explicitly asks for a detailed explanation."))
        assertEquals("user", messages[1].role)
        assertEquals("Hello there", messages[1].content)
    }

    @Test
    fun buildMessageSpecs_omitsSystemPromptWhenLanguageUnknown() {
        val messages = GroqChatRequestBuilder.buildMessageSpecs(
            userText = "Hello there",
            languageCode = null
        )

        assertEquals(1, messages.size)
        assertEquals("user", messages[0].role)
        assertFalse(messages[0].content.contains("Do not translate"))
    }
}
