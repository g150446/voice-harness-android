package com.g150446.voiceharness

import kotlin.math.max
import kotlin.math.sqrt

internal object PcmSilenceTrimmer {
    private const val BYTES_PER_SAMPLE = 2
    private const val FRAME_MS = 20
    private const val PADDING_MS = 300
    private const val MIN_AUDIO_MS = 1_000
    private const val MIN_SAVED_MS = 200
    private const val ABSOLUTE_RMS_FLOOR = 0.0015
    private const val RELATIVE_RMS_RATIO = 0.12
    private const val MIN_ACTIVE_FRAMES = 2

    fun trim(pcmData: ByteArray, sampleRate: Int): ByteArray {
        if (sampleRate <= 0 || pcmData.size % BYTES_PER_SAMPLE != 0) return pcmData
        val sampleCount = pcmData.size / BYTES_PER_SAMPLE
        if (sampleCount * 1_000L / sampleRate < MIN_AUDIO_MS) return pcmData

        val frameSamples = sampleRate * FRAME_MS / 1_000
        if (frameSamples <= 0) return pcmData
        val frameCount = (sampleCount + frameSamples - 1) / frameSamples
        val rmsValues = DoubleArray(frameCount)
        var maxRms = 0.0

        for (frame in 0 until frameCount) {
            val start = frame * frameSamples
            val end = minOf(sampleCount, start + frameSamples)
            var energy = 0.0
            for (sampleIndex in start until end) {
                val byteIndex = sampleIndex * BYTES_PER_SAMPLE
                val lo = pcmData[byteIndex].toInt() and 0xFF
                val hi = pcmData[byteIndex + 1].toInt()
                val sample = ((hi shl 8) or lo).toShort().toDouble() / 32768.0
                energy += sample * sample
            }
            val rms = if (end > start) sqrt(energy / (end - start)) else 0.0
            rmsValues[frame] = rms
            if (rms > maxRms) maxRms = rms
        }

        val threshold = max(ABSOLUTE_RMS_FLOOR, maxRms * RELATIVE_RMS_RATIO)
        val activeFrames = rmsValues.indices.filter { rmsValues[it] >= threshold }
        if (activeFrames.size < MIN_ACTIVE_FRAMES) return pcmData

        val paddingFrames = (PADDING_MS + FRAME_MS - 1) / FRAME_MS
        val firstFrame = maxOf(0, activeFrames.first() - paddingFrames)
        val lastFrameExclusive = minOf(frameCount, activeFrames.last() + paddingFrames + 1)
        val startSample = firstFrame * frameSamples
        val endSample = minOf(sampleCount, lastFrameExclusive * frameSamples)
        val savedSamples = sampleCount - (endSample - startSample)
        if (savedSamples * 1_000L / sampleRate < MIN_SAVED_MS) return pcmData

        return pcmData.copyOfRange(
            startSample * BYTES_PER_SAMPLE,
            endSample * BYTES_PER_SAMPLE
        )
    }
}
