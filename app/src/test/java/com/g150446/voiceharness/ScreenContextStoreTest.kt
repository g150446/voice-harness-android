package com.g150446.voiceharness

import com.g150446.voiceharness.assistant.AssistStructureExtractor
import com.g150446.voiceharness.assistant.ScreenContextStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenContextStoreTest {
    @Test
    fun `token is single use`() {
        ScreenContextStore.clear()
        val token = ScreenContextStore.put(ScreenContext(assistText = "hello"))
        assertEquals("hello", ScreenContextStore.take(token)?.assistText)
        assertNull(ScreenContextStore.take(token))
    }

    @Test
    fun `remove discards token`() {
        ScreenContextStore.clear()
        val token = ScreenContextStore.put(ScreenContext(assistText = "x"))
        ScreenContextStore.remove(token)
        assertNull(ScreenContextStore.take(token))
    }

    @Test
    fun `assist text truncated to 24000`() {
        val long = "a".repeat(30_000)
        val truncated = ScreenContextPrompt.truncateAssistText(long)
        assertEquals(24_000, truncated.length)
    }

    @Test
    fun `null structure yields empty extraction`() {
        val extracted = AssistStructureExtractor.extract(null)
        assertTrue(extracted.text.isEmpty())
        assertNull(extracted.sourcePackage)
    }

    @Test
    fun `jpeg must not be required for text-only screen context`() {
        val ctx = ScreenContext(assistText = "only text")
        assertTrue(ctx.hasText)
        assertFalse(ctx.hasImage)
        assertFalse(ctx.isEmpty)
    }
}
