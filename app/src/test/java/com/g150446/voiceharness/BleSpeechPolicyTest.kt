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
                rmsAfterDc = 0.02f,
                maxBandRatio = 0.50
            )
        )

        assertFalse(
            shouldRescueBleSpectrum(
                peakAfterDc = 0.07f,
                rmsAfterDc = 0.02f,
                maxBandRatio = 0.50
            )
        )
    }
}
