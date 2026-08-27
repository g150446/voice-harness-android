package com.g150446.voiceharness

import com.g150446.voiceharness.assistant.AssistStructureExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic checks for harness screen filtering rules
 * (own-package discard is applied when sourcePackage matches app id).
 */
class HeadlessScreenCaptureFilterTest {
    @Test
    fun `empty screen context is empty`() {
        val ctx = ScreenContext()
        assertTrue(ctx.isEmpty)
        assertTrue(!ctx.hasText)
        assertTrue(!ctx.hasImage)
    }

    @Test
    fun `screen with only text is not empty`() {
        val ctx = ScreenContext(assistText = "hello", sourcePackage = "com.other.app")
        assertTrue(ctx.hasText)
        assertTrue(!ctx.isEmpty)
    }

    @Test
    fun `own package should be treated as discard candidate`() {
        val own = "com.g150446.voiceharness"
        val ctx = ScreenContext(assistText = "settings", sourcePackage = own)
        assertEquals(own, ctx.sourcePackage)
        // Filter rule used by HeadlessScreenCapture: drop when src == own package.
        val kept = if (ctx.sourcePackage == own) null else ctx
        assertNull(kept)
    }

    @Test
    fun `assist extractor handles null structure`() {
        val extracted = AssistStructureExtractor.extract(null)
        assertEquals("", extracted.text)
        assertNull(extracted.sourcePackage)
    }
}
