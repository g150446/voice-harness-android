package com.g150446.voiceharness

/**
 * Counts consecutive silent Silero frames and reports when silence has lasted
 * [silenceDurationMs] (default 5 seconds). Any speech frame resets the counter.
 *
 * No longer used to drive RX 0x00 auto-stop; recording stop is firmware gesture only.
 * Kept for unit tests and possible future UI-side endpointing without device writes.
 */
internal class SilenceEndpointTracker(
    private val sampleRate: Int = 16_000,
    private val frameSize: Int = SileroVad.FRAME_SIZE,
    private val silenceDurationMs: Long = BLE_SILENCE_STOP_MS
) {
    private var consecutiveSilentSamples = 0

    val requiredSilentFrames: Int
        get() = ((silenceDurationMs * sampleRate + 1000L * frameSize - 1) /
            (1000L * frameSize)).toInt()

    fun reset() {
        consecutiveSilentSamples = 0
    }

    /** @return true when consecutive silence has reached [silenceDurationMs]. */
    fun onFrame(isSpeech: Boolean): Boolean {
        if (isSpeech) {
            consecutiveSilentSamples = 0
            return false
        }
        consecutiveSilentSamples += frameSize
        return consecutiveSilentSamples * 1_000L / sampleRate >= silenceDurationMs
    }
}
