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
                CapturePurpose.COMMAND,
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
    fun `OpenClaw mode records on single tap exactly like AI mode`() {
        for (state in VoiceState.entries) {
            assertEquals(
                singleTapRecordingCommand(InteractionMode.AI, state),
                singleTapRecordingCommand(InteractionMode.OPENCLAW, state),
            )
        }
        assertEquals(
            BLE_RX_START_RECORDING,
            singleTapRecordingCommand(InteractionMode.OPENCLAW, VoiceState.READY),
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
    }

    @Test
    fun `Harbor single tap never starts or stops recording, with or without G2`() {
        // Single tap is reserved for glass-side pagination (outside this function)
        // and for confirming a pending Harbor command; it must never itself start,
        // stop, or toggle a recording — only double tap does that (see below).
        assertEquals(
            RecordingTapAction.NONE,
            recordingTapAction(
                mode = RecordingTapMode.SINGLE,
                event = RecordingTapEvent.SINGLE,
                interactionMode = InteractionMode.HARBOR,
                g2ClientActive = false,
                state = VoiceState.READY,
            ),
        )
        assertEquals(
            RecordingTapAction.NONE,
            recordingTapAction(
                mode = RecordingTapMode.DOUBLE,
                event = RecordingTapEvent.SINGLE,
                interactionMode = InteractionMode.HARBOR,
                g2ClientActive = true,
                state = VoiceState.READY,
            ),
        )
        assertEquals(
            RecordingTapAction.NONE,
            recordingTapAction(
                mode = RecordingTapMode.SINGLE,
                event = RecordingTapEvent.SINGLE,
                interactionMode = InteractionMode.HARBOR,
                g2ClientActive = true,
                state = VoiceState.RECORDING,
                capturePurpose = CapturePurpose.COMMAND,
            ),
        )
        assertEquals(
            RecordingTapAction.NONE,
            recordingTapAction(
                mode = RecordingTapMode.SINGLE,
                event = RecordingTapEvent.SINGLE,
                interactionMode = InteractionMode.HARBOR,
                g2ClientActive = true,
                state = VoiceState.RECORDING,
                capturePurpose = CapturePurpose.AI_QUERY,
            ),
        )
        assertEquals(
            RecordingTapAction.NONE,
            recordingTapAction(
                mode = RecordingTapMode.SINGLE,
                event = RecordingTapEvent.SINGLE,
                interactionMode = InteractionMode.HARBOR,
                g2ClientActive = true,
                state = VoiceState.TRANSCRIBING,
            ),
        )
    }

    @Test
    fun `AI single tap never starts or stops recording while G2 is active`() {
        assertEquals(
            RecordingTapAction.NONE,
            recordingTapAction(
                mode = RecordingTapMode.SINGLE,
                event = RecordingTapEvent.SINGLE,
                interactionMode = InteractionMode.AI,
                g2ClientActive = true,
                state = VoiceState.READY,
            ),
        )
        assertEquals(
            RecordingTapAction.NONE,
            recordingTapAction(
                mode = RecordingTapMode.SINGLE,
                event = RecordingTapEvent.SINGLE,
                interactionMode = InteractionMode.AI,
                g2ClientActive = true,
                state = VoiceState.RECORDING,
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
    fun `G2 connection uses double tap for command recording`() {
        assertEquals(
            RecordingTapAction.START_COMMAND,
            recordingTapAction(
                mode = RecordingTapMode.SINGLE,
                event = RecordingTapEvent.DOUBLE,
                interactionMode = InteractionMode.AI,
                g2ClientActive = true,
                state = VoiceState.READY,
            ),
        )
        assertEquals(
            RecordingTapAction.START_COMMAND,
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
                capturePurpose = CapturePurpose.COMMAND,
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

    @Test
    fun `Harbor confirm uses single tap to execute and double tap to cancel`() {
        assertEquals(
            RecordingTapAction.CONFIRM_HARBOR,
            recordingTapAction(
                mode = RecordingTapMode.DOUBLE,
                event = RecordingTapEvent.SINGLE,
                interactionMode = InteractionMode.HARBOR,
                g2ClientActive = true,
                state = VoiceState.READY,
                harborConfirmPending = true,
            ),
        )
        // The prompt says 「ダブルタップで取り消す」, so it must stop rather than re-record.
        assertEquals(
            RecordingTapAction.CANCEL_HARBOR,
            recordingTapAction(
                mode = RecordingTapMode.SINGLE,
                event = RecordingTapEvent.DOUBLE,
                interactionMode = InteractionMode.HARBOR,
                g2ClientActive = true,
                state = VoiceState.READY,
                harborConfirmPending = true,
            ),
        )
    }

    @Test
    fun `clarification prompt re-records on double tap because it says so`() {
        // 「ダブルタップで言い直す」 is the only confirm prompt that starts a new recording.
        assertEquals(
            RecordingTapAction.START_COMMAND,
            recordingTapAction(
                mode = RecordingTapMode.DOUBLE,
                event = RecordingTapEvent.DOUBLE,
                interactionMode = InteractionMode.HARBOR,
                g2ClientActive = true,
                state = VoiceState.READY,
                harborConfirmPending = true,
                harborConfirmAwaitingClarification = true,
            ),
        )
    }

    @Test
    fun `Harbor confirm owns taps even when G2 client is inactive`() {
        assertEquals(
            RecordingTapAction.CONFIRM_HARBOR,
            recordingTapAction(
                mode = RecordingTapMode.SINGLE,
                event = RecordingTapEvent.SINGLE,
                interactionMode = InteractionMode.AI,
                g2ClientActive = false,
                state = VoiceState.READY,
                harborConfirmPending = true,
            ),
        )
        assertEquals(
            RecordingTapAction.CANCEL_HARBOR,
            recordingTapAction(
                mode = RecordingTapMode.SINGLE,
                event = RecordingTapEvent.DOUBLE,
                interactionMode = InteractionMode.AI,
                g2ClientActive = false,
                state = VoiceState.READY,
                harborConfirmPending = true,
            ),
        )
    }

    @Test
    fun `Harbor confirm beats single-tap recording mode used by Echo M5Stick`() {
        // Default home setting is SINGLE recording; confirm must not fall through to START_RECORDING.
        assertEquals(
            RecordingTapAction.CONFIRM_HARBOR,
            recordingTapAction(
                mode = RecordingTapMode.SINGLE,
                event = RecordingTapEvent.SINGLE,
                interactionMode = InteractionMode.AI,
                g2ClientActive = false,
                state = VoiceState.READY,
                harborConfirmPending = true,
            ),
        )
        assertEquals(
            RecordingTapAction.CONFIRM_HARBOR,
            recordingTapAction(
                mode = RecordingTapMode.SINGLE,
                event = RecordingTapEvent.SINGLE,
                interactionMode = InteractionMode.HARBOR,
                g2ClientActive = true,
                state = VoiceState.ERROR,
                harborConfirmPending = true,
            ),
        )
    }

    @Test
    fun `Harbor confirm accepts a single tap while the intent is still interpreted`() {
        // The prompt is published before the LLM returns; the tap is queued.
        assertEquals(
            RecordingTapAction.CONFIRM_HARBOR,
            recordingTapAction(
                mode = RecordingTapMode.SINGLE,
                event = RecordingTapEvent.SINGLE,
                interactionMode = InteractionMode.HARBOR,
                g2ClientActive = true,
                state = VoiceState.TRANSCRIBING,
                capturePurpose = CapturePurpose.COMMAND,
                harborConfirmPending = true,
            ),
        )
    }

    @Test
    fun `Harbor confirm double tap during interpretation interrupts instead of re-recording`() {
        assertEquals(
            RecordingTapAction.INTERRUPT,
            recordingTapAction(
                mode = RecordingTapMode.SINGLE,
                event = RecordingTapEvent.DOUBLE,
                interactionMode = InteractionMode.HARBOR,
                g2ClientActive = true,
                state = VoiceState.TRANSCRIBING,
                capturePurpose = CapturePurpose.COMMAND,
                harborConfirmPending = true,
            ),
        )
    }

    @Test
    fun `awaiting clarification still routes single tap as confirm action for ownership`() {
        // The tap is still owned by the confirm prompt; executeHarborConfirm re-asks
        // the clarifying question instead of sending (see harborConfirmOutcome).
        assertEquals(
            RecordingTapAction.CONFIRM_HARBOR,
            recordingTapAction(
                mode = RecordingTapMode.DOUBLE,
                event = RecordingTapEvent.SINGLE,
                interactionMode = InteractionMode.HARBOR,
                g2ClientActive = true,
                state = VoiceState.READY,
                harborConfirmPending = true,
            ),
        )
    }

    @Test
    fun `harbor confirm outcome separates expired, clarification and submit`() {
        assertEquals(HarborConfirmOutcome.EXPIRED, harborConfirmOutcome(null))
        assertEquals(
            HarborConfirmOutcome.NEEDS_CLARIFICATION,
            harborConfirmOutcome(
                PendingHarborCommand(
                    stt = "あれをやって",
                    args = HarborCommandArgs(
                        command = "あれをやって",
                        intentSummary = "何を実行しますか？",
                        needsClarification = true,
                        question = "何を実行しますか？",
                    ),
                ),
            ),
        )
        assertEquals(
            HarborConfirmOutcome.SUBMIT,
            harborConfirmOutcome(
                PendingHarborCommand(
                    stt = "コミットして",
                    args = HarborCommandArgs(
                        command = "コミットして",
                        intentSummary = "コミットを指示しますか？",
                    ),
                ),
            ),
        )
    }

    @Test
    fun `harbor result stays on the glass past a mirror poll tick`() {
        // The mirror repaints every HARBOR_MIRROR_POLL_MS; a shorter hold means the
        // "指示を送りました" result is wiped before it can be read, which reads as
        // the command never having run.
        assertTrue(HARBOR_RESULT_HOLD_MS > HARBOR_MIRROR_POLL_MS)
    }

    @Test
    fun `OpenClaw send confirm uses single tap to send and double tap to cancel`() {
        assertEquals(
            RecordingTapAction.CONFIRM_HARBOR,
            recordingTapAction(
                mode = RecordingTapMode.DOUBLE,
                event = RecordingTapEvent.SINGLE,
                interactionMode = InteractionMode.OPENCLAW,
                g2ClientActive = true,
                state = VoiceState.READY,
                harborConfirmPending = true,
            ),
        )
        assertEquals(
            RecordingTapAction.CANCEL_HARBOR,
            recordingTapAction(
                mode = RecordingTapMode.DOUBLE,
                event = RecordingTapEvent.DOUBLE,
                interactionMode = InteractionMode.OPENCLAW,
                g2ClientActive = true,
                state = VoiceState.READY,
                harborConfirmPending = true,
            ),
        )
    }

    @Test
    fun `OpenClaw single tap on G2 is left to glass pagination when nothing is pending`() {
        for (mode in RecordingTapMode.entries) {
            for (state in listOf(VoiceState.READY, VoiceState.ERROR, VoiceState.RESPONDING)) {
                assertEquals(
                    RecordingTapAction.NONE,
                    recordingTapAction(
                        mode = mode,
                        event = RecordingTapEvent.SINGLE,
                        interactionMode = InteractionMode.OPENCLAW,
                        g2ClientActive = true,
                        state = state,
                    ),
                )
            }
        }
    }

    @Test
    fun `EPUB mode single tap never records and double tap on G2 records a command`() {
        for (mode in RecordingTapMode.entries) {
            assertEquals(
                RecordingTapAction.NONE,
                recordingTapAction(
                    mode = mode,
                    event = RecordingTapEvent.SINGLE,
                    interactionMode = InteractionMode.EPUB,
                    g2ClientActive = false,
                    state = VoiceState.READY,
                ),
            )
        }
        assertEquals(
            RecordingTapAction.START_COMMAND,
            recordingTapAction(
                mode = RecordingTapMode.DOUBLE,
                event = RecordingTapEvent.DOUBLE,
                interactionMode = InteractionMode.EPUB,
                g2ClientActive = true,
                state = VoiceState.READY,
            ),
        )
    }
}
