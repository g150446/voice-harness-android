package com.g150446.voiceharness

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sqrt

data class BlePcmAnalysis(
    val samples: FloatArray,
    val dcOffset: Float,
    val peakBeforeDc: Float,
    val peakAfterDc: Float,
    val rmsAfterDc: Float,
    val gain: Float
)

data class SpectrumVadResult(
    val speechFrames: Int,
    val activeFrames: Int,
    val totalFrames: Int,
    val maxBandRatio: Double,
    val topBandRatios: List<Double>
) {
    val ratio: Double
        get() = if (activeFrames > 0) speechFrames.toDouble() / activeFrames else 0.0

    fun hasSpeech(minRatio: Double): Boolean = activeFrames > 0 && ratio >= minRatio
}

object BleSpeechDetector {
    private const val SILERO_TARGET_PEAK = 0.5f
    private const val MIN_PEAK_FOR_GAIN = 0.001f
    private const val ACTIVE_FRAME_ENERGY_RATIO = 0.1

    const val FFT_FRAME_SIZE = 512
    private const val FFT_HOP_SIZE = FFT_FRAME_SIZE / 2
    private const val SPEECH_LOW_HZ = 300
    private const val SPEECH_HIGH_HZ = 3400
    const val SPEECH_RATIO_THRESHOLD = 0.45
    const val SPEECH_FRAME_MIN_RATIO = 0.03

    fun analyzeBlePcm(pcmData: ByteArray): BlePcmAnalysis {
        val samples = FloatArray(pcmData.size / 2)
        var peakBeforeDc = 0f

        for (i in samples.indices) {
            val lo = pcmData[i * 2].toInt() and 0xFF
            val hi = pcmData[i * 2 + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort() / 32768f
            samples[i] = sample
            val absSample = abs(sample)
            if (absSample > peakBeforeDc) peakBeforeDc = absSample
        }

        val dcOffset = if (samples.isNotEmpty()) samples.average().toFloat() else 0f
        var peakAfterDc = 0f
        var energy = 0.0
        for (i in samples.indices) {
            val centered = samples[i] - dcOffset
            samples[i] = centered
            val absSample = abs(centered)
            if (absSample > peakAfterDc) peakAfterDc = absSample
            energy += centered * centered
        }

        val rmsAfterDc = if (samples.isNotEmpty()) sqrt(energy / samples.size).toFloat() else 0f
        val gain = if (peakAfterDc > MIN_PEAK_FOR_GAIN) SILERO_TARGET_PEAK / peakAfterDc else 1f

        return BlePcmAnalysis(
            samples = samples,
            dcOffset = dcOffset,
            peakBeforeDc = peakBeforeDc,
            peakAfterDc = peakAfterDc,
            rmsAfterDc = rmsAfterDc,
            gain = gain
        )
    }

    fun detectSpeechBySpectrum(samples: FloatArray, sampleRate: Int): SpectrumVadResult {
        if (samples.size < FFT_FRAME_SIZE) {
            return SpectrumVadResult(
                speechFrames = 0,
                activeFrames = 0,
                totalFrames = 0,
                maxBandRatio = 0.0,
                topBandRatios = emptyList()
            )
        }

        val window = hannWindow()
        val speechLowBin = maxOf(1, (SPEECH_LOW_HZ * FFT_FRAME_SIZE / sampleRate.toDouble()).toInt())
        val speechHighBin = min(
            FFT_FRAME_SIZE / 2 - 1,
            kotlin.math.ceil(SPEECH_HIGH_HZ * FFT_FRAME_SIZE / sampleRate.toDouble()).toInt()
        )

        var totalFrames = 0
        var maxBandRatio = 0.0
        var offset = 0
        var maxFrameEnergy = 0.0
        val frameBandRatios = ArrayList<Double>()
        val frameEnergies = ArrayList<Double>()

        while (offset + FFT_FRAME_SIZE <= samples.size) {
            val real = DoubleArray(FFT_FRAME_SIZE)
            val imag = DoubleArray(FFT_FRAME_SIZE)
            for (i in 0 until FFT_FRAME_SIZE) {
                real[i] = samples[offset + i] * window[i]
            }

            fftInPlace(real, imag)

            var speechEnergy = 0.0
            var frameEnergy = 0.0
            for (bin in 1 until FFT_FRAME_SIZE / 2) {
                val energy = real[bin] * real[bin] + imag[bin] * imag[bin]
                frameEnergy += energy
                if (bin in speechLowBin..speechHighBin) {
                    speechEnergy += energy
                }
            }

            val bandRatio = if (frameEnergy > 0.0) speechEnergy / frameEnergy else 0.0
            if (bandRatio > maxBandRatio) maxBandRatio = bandRatio
            if (frameEnergy > maxFrameEnergy) maxFrameEnergy = frameEnergy
            frameBandRatios += bandRatio
            frameEnergies += frameEnergy
            totalFrames++
            offset += FFT_HOP_SIZE
        }

        val activeEnergyThreshold = maxFrameEnergy * ACTIVE_FRAME_ENERGY_RATIO
        var activeFrames = 0
        var speechFrames = 0
        for (index in frameBandRatios.indices) {
            if (frameEnergies[index] >= activeEnergyThreshold) {
                activeFrames++
                if (frameBandRatios[index] >= SPEECH_RATIO_THRESHOLD) {
                    speechFrames++
                }
            }
        }

        return SpectrumVadResult(
            speechFrames = speechFrames,
            activeFrames = activeFrames,
            totalFrames = totalFrames,
            maxBandRatio = maxBandRatio,
            topBandRatios = frameBandRatios.sortedDescending().take(5)
        )
    }

    private fun hannWindow(): DoubleArray = DoubleArray(FFT_FRAME_SIZE) { index ->
        0.5 - 0.5 * cos(2.0 * PI * index / (FFT_FRAME_SIZE - 1))
    }

    private fun fftInPlace(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tmpReal = real[i]
                real[i] = real[j]
                real[j] = tmpReal

                val tmpImag = imag[i]
                imag[i] = imag[j]
                imag[j] = tmpImag
            }
        }

        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val wLenReal = cos(angle)
            val wLenImag = kotlin.math.sin(angle)
            for (start in 0 until n step len) {
                var wReal = 1.0
                var wImag = 0.0
                for (k in 0 until len / 2) {
                    val evenIndex = start + k
                    val oddIndex = evenIndex + len / 2

                    val oddReal = real[oddIndex] * wReal - imag[oddIndex] * wImag
                    val oddImag = real[oddIndex] * wImag + imag[oddIndex] * wReal

                    val evenReal = real[evenIndex]
                    val evenImag = imag[evenIndex]

                    real[evenIndex] = evenReal + oddReal
                    imag[evenIndex] = evenImag + oddImag
                    real[oddIndex] = evenReal - oddReal
                    imag[oddIndex] = evenImag - oddImag

                    val nextWReal = wReal * wLenReal - wImag * wLenImag
                    wImag = wReal * wLenImag + wImag * wLenReal
                    wReal = nextWReal
                }
            }
            len = len shl 1
        }
    }
}
