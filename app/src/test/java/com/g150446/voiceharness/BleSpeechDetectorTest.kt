package com.g150446.voiceharness

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class BleSpeechDetectorTest {

    @Test
    fun analyzeBlePcm_removesDcOffsetAndComputesGain() {
        val pcmData = buildPcm { index ->
            0.10f + (0.05f * sin(2.0 * PI * 1000.0 * index / 16_000.0)).toFloat()
        }

        val analysis = BleSpeechDetector.analyzeBlePcm(pcmData)

        assertTrue(abs(analysis.dcOffset - 0.10f) < 0.02f)
        assertTrue(analysis.peakBeforeDc > analysis.peakAfterDc)
        assertTrue(analysis.peakAfterDc > 0.03f)
        assertTrue(analysis.gain > 5f)
    }

    @Test
    fun detectSpeechBySpectrum_acceptsSpeechBandTone() {
        val samples = buildSamples(frequencyHz = 1000.0)

        val result = BleSpeechDetector.detectSpeechBySpectrum(samples, 16_000)

        assertTrue(result.hasSpeech(BleSpeechDetector.SPEECH_FRAME_MIN_RATIO))
        assertTrue(result.maxBandRatio > BleSpeechDetector.SPEECH_RATIO_THRESHOLD)
    }

    @Test
    fun detectSpeechBySpectrum_acceptsQuietSpeechBandTone() {
        val samples = buildSamples(
            frequencyHz = 1000.0,
            amplitude = 0.03f
        )

        val result = BleSpeechDetector.detectSpeechBySpectrum(samples, 16_000)

        assertTrue(result.hasSpeech(BleSpeechDetector.SPEECH_FRAME_MIN_RATIO))
        assertTrue(result.maxBandRatio > BleSpeechDetector.SPEECH_RATIO_THRESHOLD)
    }

    @Test
    fun detectSpeechBySpectrum_rejectsLowFrequencyTone() {
        val samples = buildSamples(frequencyHz = 100.0)

        val result = BleSpeechDetector.detectSpeechBySpectrum(samples, 16_000)

        assertFalse(result.hasSpeech(BleSpeechDetector.SPEECH_FRAME_MIN_RATIO))
        assertTrue(result.maxBandRatio < BleSpeechDetector.SPEECH_RATIO_THRESHOLD)
    }

    private fun buildSamples(
        frequencyHz: Double,
        sampleRate: Int = 16_000,
        sampleCount: Int = 4096,
        amplitude: Float = 0.4f
    ): FloatArray = FloatArray(sampleCount) { index ->
        (amplitude * sin(2.0 * PI * frequencyHz * index / sampleRate)).toFloat()
    }

    private fun buildPcm(
        sampleRate: Int = 16_000,
        sampleCount: Int = 4096,
        generator: (Int) -> Float
    ): ByteArray {
        val bytes = ByteArray(sampleCount * 2)
        for (index in 0 until sampleCount) {
            val sample = generator(index).coerceIn(-0.999f, 0.999f)
            val pcm = (sample * Short.MAX_VALUE).toInt().toShort()
            bytes[index * 2] = (pcm.toInt() and 0xFF).toByte()
            bytes[index * 2 + 1] = ((pcm.toInt() shr 8) and 0xFF).toByte()
        }
        return bytes
    }
}
