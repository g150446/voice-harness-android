package com.g150446.voiceharness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceProcessorDoubleTapTest {

    @Test
    fun `double tap interrupts recording and post-recording pipeline states`() {
        assertFalse(shouldInterruptOnDoubleTap(VoiceState.READY))
        assertTrue(shouldInterruptOnDoubleTap(VoiceState.RECORDING))
        assertTrue(shouldInterruptOnDoubleTap(VoiceState.TRANSCRIBING))
        assertTrue(shouldInterruptOnDoubleTap(VoiceState.RESPONDING))
        assertTrue(shouldInterruptOnDoubleTap(VoiceState.SPEAKING))
        assertFalse(shouldInterruptOnDoubleTap(VoiceState.ERROR))
    }

    @Test
    fun `single tap is suppressed for one second after double tap`() {
        assertFalse(shouldSuppressSingleTapAfterDouble(nowElapsedMs = 5_000L, lastDoubleTapElapsedMs = 0L))
        assertTrue(shouldSuppressSingleTapAfterDouble(nowElapsedMs = 5_000L, lastDoubleTapElapsedMs = 5_000L))
        assertTrue(shouldSuppressSingleTapAfterDouble(nowElapsedMs = 5_500L, lastDoubleTapElapsedMs = 5_000L))
        assertTrue(shouldSuppressSingleTapAfterDouble(nowElapsedMs = 5_999L, lastDoubleTapElapsedMs = 5_000L))
        assertFalse(shouldSuppressSingleTapAfterDouble(nowElapsedMs = 6_000L, lastDoubleTapElapsedMs = 5_000L))
        assertFalse(shouldSuppressSingleTapAfterDouble(nowElapsedMs = 4_000L, lastDoubleTapElapsedMs = 5_000L))
    }

    @Test
    fun `single tap recording is suppressed while reader mode is on`() {
        assertNull(
            singleTapRecordingCommand(
                readerModeEnabled = true,
                state = VoiceState.READY,
            ),
        )
        assertNull(
            singleTapRecordingCommand(
                readerModeEnabled = true,
                state = VoiceState.RECORDING,
            ),
        )
    }

    @Test
    fun `single tap requests host start or stop when reader mode is off`() {
        assertEquals(
            BLE_RX_START_RECORDING,
            singleTapRecordingCommand(false, VoiceState.READY),
        )
        assertEquals(
            BLE_RX_STOP_RECORDING,
            singleTapRecordingCommand(false, VoiceState.RECORDING),
        )
        assertEquals(
            BLE_RX_START_RECORDING,
            singleTapRecordingCommand(false, VoiceState.SPEAKING),
        )
    }

    @Test
    fun `double tap toggles reader mode when idle and G2 is connected`() {
        assertEquals(
            ReaderModeDoubleTapAction.ENABLE,
            readerModeDoubleTapAction(
                readerModeEnabled = false,
                g2ClientActive = true,
                state = VoiceState.READY,
            ),
        )
        assertEquals(
            ReaderModeDoubleTapAction.DISABLE,
            readerModeDoubleTapAction(
                readerModeEnabled = true,
                g2ClientActive = true,
                state = VoiceState.READY,
            ),
        )
        assertEquals(
            ReaderModeDoubleTapAction.DISABLE,
            readerModeDoubleTapAction(
                readerModeEnabled = true,
                g2ClientActive = false,
                state = VoiceState.READY,
            ),
        )
        assertEquals(
            ReaderModeDoubleTapAction.NONE,
            readerModeDoubleTapAction(
                readerModeEnabled = true,
                g2ClientActive = true,
                state = VoiceState.SPEAKING,
            ),
        )
    }

    @Test
    fun `double tap does not enable reader mode without G2`() {
        assertEquals(
            ReaderModeDoubleTapAction.NONE,
            readerModeDoubleTapAction(
                readerModeEnabled = false,
                g2ClientActive = false,
                state = VoiceState.READY,
            ),
        )
    }
}
