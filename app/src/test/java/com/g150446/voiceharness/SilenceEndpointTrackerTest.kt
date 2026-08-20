package com.g150446.voiceharness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceEndpointTrackerTest {

    @Test
    fun reachesFiveSecondsAfterRequiredSilentFrames() {
        val tracker = SilenceEndpointTracker()
        val needed = tracker.requiredSilentFrames
        assertEquals(157, needed)

        repeat(needed - 1) {
            assertFalse(tracker.onFrame(isSpeech = false))
        }
        assertTrue(tracker.onFrame(isSpeech = false))
    }

    @Test
    fun speechResetsConsecutiveSilence() {
        val tracker = SilenceEndpointTracker()
        repeat(tracker.requiredSilentFrames - 1) {
            assertFalse(tracker.onFrame(isSpeech = false))
        }
        assertFalse(tracker.onFrame(isSpeech = true))
        repeat(tracker.requiredSilentFrames - 1) {
            assertFalse(tracker.onFrame(isSpeech = false))
        }
        assertTrue(tracker.onFrame(isSpeech = false))
    }

    @Test
    fun resetClearsPartialSilence() {
        val tracker = SilenceEndpointTracker()
        repeat(40) { tracker.onFrame(isSpeech = false) }
        tracker.reset()
        repeat(tracker.requiredSilentFrames - 1) {
            assertFalse(tracker.onFrame(isSpeech = false))
        }
        assertTrue(tracker.onFrame(isSpeech = false))
    }
}
