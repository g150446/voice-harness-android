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
        assertTrue(decision.skipSpectrum)
    }

    @Test
    fun decideBleSileroOutcome_doesNotSkipSpectrumForLowRatioSpeech() {
        val decision = decideBleSileroOutcome(
            speechFrames = 1,
            totalFrames = 40,
            maxProb = 0.18f
        )

        assertFalse(decision.accepted)
        assertFalse(decision.skipSpectrum)
    }

    @Test
    fun shouldRescueBleSpectrum_requiresAllRescueSignals() {
        // Meets new thresholds: peak >= 0.15, rms >= 0.04
        assertTrue(
            shouldRescueBleSpectrum(
                peakAfterDc = 0.16f,
                rmsAfterDc = 0.05f,
                maxBandRatio = 0.50
            )
        )

        // peak below new threshold (0.14 < 0.15) → rejected
        assertFalse(
            shouldRescueBleSpectrum(
                peakAfterDc = 0.14f,
                rmsAfterDc = 0.05f,
                maxBandRatio = 0.50
            )
        )

        // rms below new threshold (0.03 < 0.04) → rejected
        assertFalse(
            shouldRescueBleSpectrum(
                peakAfterDc = 0.16f,
                rmsAfterDc = 0.03f,
                maxBandRatio = 0.50
            )
        )
    }
}
