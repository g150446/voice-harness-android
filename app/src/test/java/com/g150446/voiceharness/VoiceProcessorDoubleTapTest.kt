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
    fun `single tap recording is suppressed outside AI mode`() {
        assertNull(
            singleTapRecordingCommand(
                interactionMode = InteractionMode.READER,
                state = VoiceState.READY,
            ),
        )
        assertNull(
            singleTapRecordingCommand(
                interactionMode = InteractionMode.HARBOR,
                state = VoiceState.RECORDING,
            ),
        )
    }

    @Test
    fun `single tap requests host start or stop when reader mode is off`() {
        assertEquals(
            BLE_RX_START_RECORDING,
            singleTapRecordingCommand(InteractionMode.AI, VoiceState.READY),
        )
        assertEquals(
            BLE_RX_STOP_RECORDING,
            singleTapRecordingCommand(InteractionMode.AI, VoiceState.RECORDING),
        )
        assertEquals(
            BLE_RX_START_RECORDING,
            singleTapRecordingCommand(InteractionMode.AI, VoiceState.SPEAKING),
        )
    }
}
