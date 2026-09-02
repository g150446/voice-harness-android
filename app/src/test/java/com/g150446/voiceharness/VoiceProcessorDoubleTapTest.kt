package com.g150446.voiceharness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceProcessorDoubleTapTest {

    @Test
    fun `double tap interrupts only post-recording pipeline states`() {
        assertFalse(shouldInterruptOnDoubleTap(VoiceState.READY))
        assertFalse(shouldInterruptOnDoubleTap(VoiceState.RECORDING))
        assertTrue(shouldInterruptOnDoubleTap(VoiceState.TRANSCRIBING))
        assertTrue(shouldInterruptOnDoubleTap(VoiceState.RESPONDING))
        assertTrue(shouldInterruptOnDoubleTap(VoiceState.SPEAKING))
        assertFalse(shouldInterruptOnDoubleTap(VoiceState.ERROR))
    }

    @Test
    fun `single tap recording is suppressed while passthrough is on`() {
        assertNull(
            singleTapRecordingCommand(
                passthroughEnabled = true,
                state = VoiceState.READY,
            ),
        )
        assertNull(
            singleTapRecordingCommand(
                passthroughEnabled = true,
                state = VoiceState.RECORDING,
            ),
        )
    }

    @Test
    fun `single tap requests host start or stop when passthrough is off`() {
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
    fun `double tap toggles passthrough when idle`() {
        assertEquals(
            PassthroughDoubleTapAction.ENABLE,
            passthroughDoubleTapAction(passthroughEnabled = false, state = VoiceState.READY),
        )
        assertEquals(
            PassthroughDoubleTapAction.DISABLE,
            passthroughDoubleTapAction(passthroughEnabled = true, state = VoiceState.READY),
        )
        assertEquals(
            PassthroughDoubleTapAction.NONE,
            passthroughDoubleTapAction(passthroughEnabled = true, state = VoiceState.SPEAKING),
        )
    }
}
