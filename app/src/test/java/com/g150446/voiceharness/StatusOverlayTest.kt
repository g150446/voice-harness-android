package com.g150446.voiceharness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusOverlayTest {
    @Test
    fun `recording takes priority over reading passthrough`() {
        val status = overlayStatusFor(
            voiceState = VoiceState.RECORDING,
            glassesState = readingState(page = 2, pageCount = 4),
        )
        assertTrue(status is HarnessOverlayStatus.Recording)
    }

    @Test
    fun `active reading session exposes current page`() {
        val status = overlayStatusFor(
            voiceState = VoiceState.READY,
            glassesState = readingState(page = 2, pageCount = 4),
        )
        assertEquals(HarnessOverlayStatus.ReadingPassthrough(2, 4), status)
        assertEquals("リーダー 2/4", readingPassthroughOverlayLabel(2, 4))
    }

    @Test
    fun `inactive reading session hides overlay`() {
        assertNull(
            overlayStatusFor(
                voiceState = VoiceState.READY,
                glassesState = SmartGlassesState(),
            )
        )
    }

    @Test
    fun `invalid page is clamped for display`() {
        val status = overlayStatusFor(
            voiceState = VoiceState.READY,
            glassesState = readingState(page = 8, pageCount = 3),
        )
        assertEquals(HarnessOverlayStatus.ReadingPassthrough(3, 3), status)
    }

    private fun readingState(page: Int, pageCount: Int) = SmartGlassesState(
        connected = true,
        readingPassthroughActive = true,
        readingPage = page,
        readingPageCount = pageCount,
    )
}
