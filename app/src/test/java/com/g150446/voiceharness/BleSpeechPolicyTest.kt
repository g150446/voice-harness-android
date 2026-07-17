package com.g150446.voiceharness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleSpeechPolicyTest {

    @Test
    fun decideBleSileroOutcome_acceptsWhenSpeechRatioClearsThreshold() {
        val decision = decideBleSileroOutcome(
            speechFrames = 3,
            totalFrames = 20,
            maxProb = 0.2f
        )

        assertTrue(decision.accepted)
        assertEquals(null, decision.spectrumReason)
    }

    @Test
    fun decideBleSileroOutcome_requestsSpectrumForQuietSpeech() {
        val decision = decideBleSileroOutcome(
            speechFrames = 1,
            totalFrames = 40,
            maxProb = 0.18f
        )

        assertFalse(decision.accepted)
        assertEquals("Silero below speech ratio threshold", decision.spectrumReason)
    }

    @Test
    fun decideBleSileroOutcome_marksStuckSileroSeparately() {
        val decision = decideBleSileroOutcome(
            speechFrames = 0,
            totalFrames = 40,
            maxProb = 0.005f
        )

        assertFalse(decision.accepted)
        assertEquals("Silero output stuck near zero", decision.spectrumReason)
    }

    @Test
    fun shouldRescueBleSpectrum_requiresAllRescueSignals() {
        assertTrue(
            shouldRescueBleSpectrum(
                peakAfterDc = 0.09f,
                rmsAfterDc = 0.009f,
                maxBandRatio = 0.50
            )
        )

        assertFalse(
            shouldRescueBleSpectrum(
                peakAfterDc = 0.07f,
                rmsAfterDc = 0.009f,
                maxBandRatio = 0.50
            )
        )

        assertFalse(
            shouldRescueBleSpectrum(
                peakAfterDc = 0.09f,
                rmsAfterDc = 0.007f,
                maxBandRatio = 0.50
            )
        )
    }

    @Test
    fun shouldRescueBleSpectrum_acceptsObservedBleSpeechLevels() {
        // Logged failure cases that previously skipped Groq despite real speech.
        assertTrue(
            shouldRescueBleSpectrum(
                peakAfterDc = 0.0950f,
                rmsAfterDc = 0.0091f,
                maxBandRatio = 0.766
            )
        )
        assertTrue(
            shouldRescueBleSpectrum(
                peakAfterDc = 0.0940f,
                rmsAfterDc = 0.0107f,
                maxBandRatio = 0.650
            )
        )
        // Genuine near-silence should still be rejected.
        assertFalse(
            shouldRescueBleSpectrum(
                peakAfterDc = 0.0118f,
                rmsAfterDc = 0.0020f,
                maxBandRatio = 0.427
            )
        )
    }
}
