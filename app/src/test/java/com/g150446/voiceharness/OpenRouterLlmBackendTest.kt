package com.g150446.voiceharness

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterChatCancelAndErrorTest {
    @Test
    fun `safe error does not include request body secrets`() {
        val err = OpenRouterChatRequestBuilder.safeHttpError(
            500,
            """internal Authorization: Bearer sk-secret-value-here""",
        )
        assertTrue(err.contains("500"))
        assertFalse(err.contains("sk-secret-value-here"))
    }

    @Test
    fun `safe error handles blank body`() {
        val err = OpenRouterChatRequestBuilder.safeHttpError(429, null)
        assertTrue(err.contains("429"))
    }
}
