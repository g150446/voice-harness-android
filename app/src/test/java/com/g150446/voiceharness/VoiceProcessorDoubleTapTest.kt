package com.g150446.voiceharness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceProcessorDoubleTapTest {

    @Test
    fun `recording tap mode storage defaults to single and restores known values`() {
        assertEquals(RecordingTapMode.SINGLE, RecordingTapMode.fromStorage(null))
        assertEquals(RecordingTapMode.SINGLE, RecordingTapMode.fromStorage("unknown"))
        assertEquals(RecordingTapMode.SINGLE, RecordingTapMode.fromStorage("SINGLE"))
        assertEquals(RecordingTapMode.DOUBLE, RecordingTapMode.fromStorage("DOUBLE"))
    }

    @Test
    fun `double tap interrupts recording and post-recording pipeline states`() {
        assertFalse(shouldInterruptOnDoubleTap(VoiceState.READY))
        assertTrue(shouldInterruptOnDoubleTap(VoiceState.RECORDING))
        assertFalse(
            shouldInterruptOnDoubleTap(
                VoiceState.RECORDING,
                CapturePurpose.MODE_SWITCH,
            ),
        )
        assertTrue(shouldInterruptOnDoubleTap(VoiceState.TRANSCRIBING))
        assertTrue(shouldInterruptOnDoubleTap(VoiceState.RESPONDING))
        assertTrue(shouldInterruptOnDoubleTap(VoiceState.SPEAKING))
        assertFalse(shouldInterruptOnDoubleTap(VoiceState.ERROR))
    }

    @Test
    fun `single tap is suppressed for two seconds after double tap`() {
        assertFalse(shouldSuppressSingleTapAfterDouble(nowElapsedMs = 5_000L, lastDoubleTapElapsedMs = 0L))
        assertTrue(shouldSuppressSingleTapAfterDouble(nowElapsedMs = 5_000L, lastDoubleTapElapsedMs = 5_000L))
        assertTrue(shouldSuppressSingleTapAfterDouble(nowElapsedMs = 5_500L, lastDoubleTapElapsedMs = 5_000L))
        assertTrue(shouldSuppressSingleTapAfterDouble(nowElapsedMs = 6_999L, lastDoubleTapElapsedMs = 5_000L))
        assertFalse(shouldSuppressSingleTapAfterDouble(nowElapsedMs = 7_000L, lastDoubleTapElapsedMs = 5_000L))
        assertFalse(shouldSuppressSingleTapAfterDouble(nowElapsedMs = 4_000L, lastDoubleTapElapsedMs = 5_000L))
        assertEquals(2_000L, SINGLE_TAP_SUPPRESS_AFTER_DOUBLE_MS)
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

    @Test
    fun `single mode routes single tap to recording and ignores double without G2`() {
        assertEquals(
            RecordingTapAction.START_RECORDING,
            recordingTapAction(
                mode = RecordingTapMode.SINGLE,
                event = RecordingTapEvent.SINGLE,
                interactionMode = InteractionMode.AI,
                g2ClientActive = false,
                state = VoiceState.READY,
            ),
        )
        assertEquals(
            RecordingTapAction.STOP_RECORDING,
            recordingTapAction(
                mode = RecordingTapMode.SINGLE,
                event = RecordingTapEvent.SINGLE,
                interactionMode = InteractionMode.AI,
                g2ClientActive = false,
                state = VoiceState.RECORDING,
            ),
        )
        assertEquals(
            RecordingTapAction.NONE,
            recordingTapAction(
                mode = RecordingTapMode.SINGLE,
                event = RecordingTapEvent.DOUBLE,
                interactionMode = InteractionMode.AI,
                g2ClientActive = false,
                state = VoiceState.RECORDING,
            ),
        )
        assertEquals(
            RecordingTapAction.NONE,
            recordingTapAction(
                mode = RecordingTapMode.SINGLE,
                event = RecordingTapEvent.SINGLE,
                interactionMode = InteractionMode.HARBOR,
                g2ClientActive = true,
                state = VoiceState.READY,
            ),
        )
    }

    @Test
    fun `double mode without G2 starts or stops recording and ignores single`() {
        assertEquals(
            RecordingTapAction.NONE,
            recordingTapAction(
                mode = RecordingTapMode.DOUBLE,
                event = RecordingTapEvent.SINGLE,
                interactionMode = InteractionMode.AI,
                g2ClientActive = false,
                state = VoiceState.READY,
            ),
        )
        assertEquals(
            RecordingTapAction.START_RECORDING,
            recordingTapAction(
                mode = RecordingTapMode.DOUBLE,
                event = RecordingTapEvent.DOUBLE,
                interactionMode = InteractionMode.AI,
                g2ClientActive = false,
                state = VoiceState.READY,
            ),
        )
        assertEquals(
            RecordingTapAction.STOP_RECORDING,
            recordingTapAction(
                mode = RecordingTapMode.DOUBLE,
                event = RecordingTapEvent.DOUBLE,
                interactionMode = InteractionMode.AI,
                g2ClientActive = false,
                state = VoiceState.RECORDING,
            ),
        )
        assertEquals(
            RecordingTapAction.INTERRUPT,
            recordingTapAction(
                mode = RecordingTapMode.DOUBLE,
                event = RecordingTapEvent.DOUBLE,
                interactionMode = InteractionMode.AI,
                g2ClientActive = false,
                state = VoiceState.TRANSCRIBING,
            ),
        )
    }

    @Test
    fun `G2 connection uses double tap for mode-switch recording`() {
        assertEquals(
            RecordingTapAction.START_MODE_SWITCH,
            recordingTapAction(
                mode = RecordingTapMode.SINGLE,
                event = RecordingTapEvent.DOUBLE,
                interactionMode = InteractionMode.AI,
                g2ClientActive = true,
                state = VoiceState.READY,
            ),
        )
        assertEquals(
            RecordingTapAction.START_MODE_SWITCH,
            recordingTapAction(
                mode = RecordingTapMode.DOUBLE,
                event = RecordingTapEvent.DOUBLE,
                interactionMode = InteractionMode.HARBOR,
                g2ClientActive = true,
                state = VoiceState.READY,
            ),
        )
        assertEquals(
            RecordingTapAction.STOP_RECORDING,
            recordingTapAction(
                mode = RecordingTapMode.SINGLE,
                event = RecordingTapEvent.DOUBLE,
                interactionMode = InteractionMode.AI,
                g2ClientActive = true,
                state = VoiceState.RECORDING,
                capturePurpose = CapturePurpose.MODE_SWITCH,
            ),
        )
        assertEquals(
            RecordingTapAction.INTERRUPT,
            recordingTapAction(
                mode = RecordingTapMode.SINGLE,
                event = RecordingTapEvent.DOUBLE,
                interactionMode = InteractionMode.AI,
                g2ClientActive = true,
                state = VoiceState.RECORDING,
            ),
        )
    }
}
