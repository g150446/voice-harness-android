package com.g150446.voiceharness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleSpeechPolicyTest {

    @Test
    fun isBlePcmCaptureComplete_acceptsNormalCapture() {
        assertTrue(isBlePcmCaptureComplete(recordingDurationMs = 5_000, pcmDurationMs = 4_700))
    }

    @Test
    fun isBlePcmCaptureComplete_rejectsObservedTruncatedCaptures() {
        assertFalse(isBlePcmCaptureComplete(recordingDurationMs = 5_050, pcmDurationMs = 580))
        assertFalse(isBlePcmCaptureComplete(recordingDurationMs = 3_570, pcmDurationMs = 1_340))
    }

    @Test
    fun isBlePcmCaptureComplete_defersShortTapToVad() {
        assertTrue(isBlePcmCaptureComplete(recordingDurationMs = 800, pcmDurationMs = 200))
    }

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
        assertTrue(decision.sileroStuck)
    }

    @Test
    fun decideBleSileroOutcome_treatsWeakMaxProbAsStuck() {
        // Field: maxProb=0.032 with 0 speech frames — still not usable Silero output.
        val decision = decideBleSileroOutcome(
            speechFrames = 0,
            totalFrames = 21,
            maxProb = 0.032f
        )
        assertFalse(decision.accepted)
        assertTrue(decision.sileroStuck)
    }

    @Test
    fun shouldRescueBleSpectrum_acceptsQuietBleSpeechEnergy() {
        // Field quiet speech rejected before: peak=0.0215 rms=0.0138 band=0.035
        assertTrue(
            shouldRescueBleSpectrum(
                peakAfterDc = 0.0215f,
                rmsAfterDc = 0.0138f,
                maxBandRatio = 0.035,
                sileroStuck = true
            )
        )
        // 2nd attempt after first success (quieter BLE level)
        assertTrue(
            shouldRescueBleSpectrum(
                peakAfterDc = 0.0085f,
                rmsAfterDc = 0.0013f,
                maxBandRatio = 0.478,
                sileroStuck = true
            )
        )
        assertTrue(
            shouldRescueBleSpectrum(
                peakAfterDc = 0.0383f,
                rmsAfterDc = 0.0146f,
                maxBandRatio = 0.049,
                sileroStuck = true
            )
        )
    }

    @Test
    fun shouldRescueBleSpectrum_rejectsNearSilence() {
        assertFalse(
            shouldRescueBleSpectrum(
                peakAfterDc = 0.0051f,
                rmsAfterDc = 0.0008f,
                maxBandRatio = 0.387,
                sileroStuck = true
            )
        )
        assertFalse(
            shouldRescueBleSpectrum(
                peakAfterDc = 0.0040f,
                rmsAfterDc = 0.0009f,
                maxBandRatio = 0.427
            )
        )
    }
}
