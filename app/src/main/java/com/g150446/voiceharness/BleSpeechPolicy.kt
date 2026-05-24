package com.g150446.voiceharness

internal const val SILERO_SPEECH_THRESHOLD = 0.5f
internal const val SILERO_FRAME_MIN_RATIO = 0.05
internal const val SILERO_STUCK_MAX_PROB = 0.01f
internal const val BLE_RESCUE_PEAK_THRESHOLD = 0.08f
internal const val BLE_RESCUE_RMS_THRESHOLD = 0.015f
internal const val BLE_RESCUE_BAND_RATIO_THRESHOLD = 0.48

internal data class BleSileroDecision(
    val accepted: Boolean,
    val spectrumReason: String? = null
)

internal fun decideBleSileroOutcome(
    speechFrames: Int,
    totalFrames: Int,
    maxProb: Float
): BleSileroDecision {
    val ratio = if (totalFrames > 0) speechFrames.toDouble() / totalFrames else 0.0
    if (ratio >= SILERO_FRAME_MIN_RATIO) {
        return BleSileroDecision(accepted = true)
    }

    val reason = if (maxProb <= SILERO_STUCK_MAX_PROB) {
        "Silero output stuck near zero"
    } else {
        "Silero below speech ratio threshold"
    }
    return BleSileroDecision(
        accepted = false,
        spectrumReason = reason
    )
}

internal fun shouldRescueBleSpectrum(
    peakAfterDc: Float?,
    rmsAfterDc: Float?,
    maxBandRatio: Double
): Boolean {
    if (peakAfterDc == null || rmsAfterDc == null) return false
    return maxBandRatio >= BLE_RESCUE_BAND_RATIO_THRESHOLD &&
        peakAfterDc >= BLE_RESCUE_PEAK_THRESHOLD &&
        rmsAfterDc >= BLE_RESCUE_RMS_THRESHOLD
}
