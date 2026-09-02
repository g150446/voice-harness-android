package com.g150446.voiceharness

internal const val SILERO_SPEECH_THRESHOLD = 0.5f
internal const val SILERO_FRAME_MIN_RATIO = 0.05
internal const val SILERO_STUCK_MAX_PROB = 0.05f
internal const val BLE_RESCUE_PEAK_THRESHOLD = 0.03f
internal const val BLE_RESCUE_RMS_THRESHOLD = 0.006f
internal const val BLE_RESCUE_BAND_RATIO_THRESHOLD = 0.35
/**
 * Energy rescue is only for Silero stuck on real quiet speech.
 * Field quiet speech: peak=0.0215/rms=0.0138.
 * Near-silence: peak≈0.005/rms≈0.0012 — must stay below these floors.
 * The previous 0.0085/0.0013 clip is indistinguishable from silence.
 */
internal const val BLE_ENERGY_RESCUE_PEAK_THRESHOLD = 0.015f
internal const val BLE_ENERGY_RESCUE_RMS_THRESHOLD = 0.004f
/** Legacy: streaming silence no longer auto-stops BLE recording (stop = FW TX 0x02). */
internal const val BLE_SILENCE_STOP_MS = 5_000L
/** Host-authorized stop (single tap when recording; FW 0.0.94+). */
internal const val BLE_RX_STOP_RECORDING = 0x00.toByte()
/** Host-authorized start (single tap when idle; FW 0.0.94+). */
internal const val BLE_RX_START_RECORDING = 0x01.toByte()
internal const val BLE_CAPTURE_CHECK_MIN_DURATION_MS = 1_000L
internal const val BLE_CAPTURE_MIN_COMPLETENESS_RATIO = 0.70

/**
 * Reject a long BLE recording when far less PCM arrived than wall-clock time implies.
 * Short taps are left to the normal minimum-length and VAD checks.
 */
internal fun isBlePcmCaptureComplete(
    recordingDurationMs: Long,
    pcmDurationMs: Long
): Boolean {
    if (recordingDurationMs < BLE_CAPTURE_CHECK_MIN_DURATION_MS) return true
    return pcmDurationMs.toDouble() / recordingDurationMs >= BLE_CAPTURE_MIN_COMPLETENESS_RATIO
}

internal data class BleSileroDecision(
    val accepted: Boolean,
    val spectrumReason: String? = null,
    val sileroStuck: Boolean = false
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

    val stuck = maxProb <= SILERO_STUCK_MAX_PROB
    val reason = if (stuck) {
        "Silero output stuck near zero"
    } else {
        "Silero below speech ratio threshold"
    }
    return BleSileroDecision(
        accepted = false,
        spectrumReason = reason,
        sileroStuck = stuck
    )
}

internal fun shouldRescueBleSpectrum(
    peakAfterDc: Float?,
    rmsAfterDc: Float?,
    maxBandRatio: Double,
    sileroStuck: Boolean = false
): Boolean {
    if (peakAfterDc == null || rmsAfterDc == null) return false
    if (sileroStuck) {
        return peakAfterDc >= BLE_ENERGY_RESCUE_PEAK_THRESHOLD &&
            rmsAfterDc >= BLE_ENERGY_RESCUE_RMS_THRESHOLD
    }
    return maxBandRatio >= BLE_RESCUE_BAND_RATIO_THRESHOLD &&
        peakAfterDc >= BLE_RESCUE_PEAK_THRESHOLD &&
        rmsAfterDc >= BLE_RESCUE_RMS_THRESHOLD
}
