package com.g150446.voiceharness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartGlassesDisplayModeTest {
    @Test
    fun `normal response keeps existing finite display behavior`() {
        assertEquals(300, SmartGlassesDisplayMode.RESPONSE.timeoutSeconds)
        assertTrue(SmartGlassesDisplayMode.RESPONSE.autoRelease)
    }

    @Test
    fun `reading passthrough stays visible without auto release`() {
        assertEquals(0, SmartGlassesDisplayMode.READING_PASSTHROUGH.timeoutSeconds)
        assertFalse(SmartGlassesDisplayMode.READING_PASSTHROUGH.autoRelease)
    }

    @Test
    fun `reading display restores when connected and control is free`() {
        assertTrue(
            shouldRestoreReadingDisplay(
                readingActive = true,
                available = true,
                linked = true,
                connected = true,
                controlledByOther = false,
                controlledByMe = false,
                displaying = false,
            )
        )
    }

    @Test
    fun `reading display waits while another app has control`() {
        assertFalse(
            shouldRestoreReadingDisplay(
                readingActive = true,
                available = true,
                linked = true,
                connected = true,
                controlledByOther = true,
                controlledByMe = false,
                displaying = false,
            )
        )
    }

    @Test
    fun `reading display does not resend while already visible`() {
        assertFalse(
            shouldRestoreReadingDisplay(
                readingActive = true,
                available = true,
                linked = true,
                connected = true,
                controlledByOther = false,
                controlledByMe = true,
                displaying = true,
            )
        )
    }
}
