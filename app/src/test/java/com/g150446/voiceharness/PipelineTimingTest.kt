package com.g150446.voiceharness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineTimingTest {

    @Test
    fun silentSkipDoesNotStartOrCommit() {
        assertEquals(PipelineTimingAction.NONE, pipelineTimingAction(PipelineTimingEvent.SILENT_SKIP))
        assertEquals(PipelineTimingAction.NONE, pipelineTimingAction(PipelineTimingEvent.INCOMPLETE_CAPTURE))
    }

    @Test
    fun speechAcceptedStartsTiming() {
        assertEquals(PipelineTimingAction.START, pipelineTimingAction(PipelineTimingEvent.SPEECH_ACCEPTED))
    }

    @Test
    fun successfulResponsesCommitTiming() {
        assertEquals(PipelineTimingAction.COMMIT, pipelineTimingAction(PipelineTimingEvent.CHAT_RESPONSE))
        assertEquals(
            PipelineTimingAction.COMMIT,
            pipelineTimingAction(PipelineTimingEvent.CONVERSATION_RESET_CONFIRMATION)
        )
        assertEquals(
            PipelineTimingAction.COMMIT,
            pipelineTimingAction(PipelineTimingEvent.REMINDER_CONFIRMATION)
        )
        assertEquals(
            PipelineTimingAction.COMMIT,
            pipelineTimingAction(PipelineTimingEvent.REMINDER_ERROR)
        )
    }

    @Test
    fun failuresDiscardTiming() {
        val failures = listOf(
            PipelineTimingEvent.WAV_FAILURE,
            PipelineTimingEvent.MODEL_READY_FAILURE,
            PipelineTimingEvent.ASR_FAILURE,
            PipelineTimingEvent.ASR_GARBAGE,
            PipelineTimingEvent.CHAT_FAILURE,
            PipelineTimingEvent.EXCEPTION
        )
        failures.forEach { event ->
            assertEquals(event.name, PipelineTimingAction.DISCARD, pipelineTimingAction(event))
        }
    }

    @Test
    fun commitRecordsElapsedTime() {
        val tracker = PipelineTimingTracker()
        tracker.start(1_000L)
        val elapsed = tracker.commit(3_400L)
        assertEquals(2_400L, elapsed)
        assertEquals(2_400L, tracker.lastCommittedMs)
        assertFalse(tracker.isRunning())
    }

    @Test
    fun startAtZeroIsValid() {
        val tracker = PipelineTimingTracker()
        tracker.start(0L)
        assertTrue(tracker.isRunning())
        assertEquals(800L, tracker.commit(800L))
    }

    @Test
    fun discardKeepsPreviousCommittedValue() {
        val tracker = PipelineTimingTracker()
        tracker.start(0L)
        tracker.commit(1_500L)
        assertEquals(1_500L, tracker.lastCommittedMs)

        tracker.start(2_000L)
        assertTrue(tracker.isRunning())
        tracker.discard()
        assertFalse(tracker.isRunning())
        assertEquals(1_500L, tracker.lastCommittedMs)
        assertNull(tracker.commit(4_000L))
    }

    @Test
    fun discardIfRunningDoesNotClearCommittedValue() {
        val tracker = PipelineTimingTracker()
        tracker.start(0L)
        tracker.commit(800L)
        tracker.discardIfRunning()
        assertEquals(800L, tracker.lastCommittedMs)
    }

    @Test
    fun formatPipelineTimingUsesSeconds() {
        assertEquals("処理時間: 2.4秒", formatPipelineTiming(2_400L))
        assertEquals("処理時間: 0.8秒", formatPipelineTiming(800L))
    }
}
