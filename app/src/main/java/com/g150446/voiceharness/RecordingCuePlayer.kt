package com.g150446.voiceharness

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log

/**
 * Plays short, distinct recording cues through the device's media audio stream.
 */
internal class RecordingCuePlayer {
    private var toneGenerator: ToneGenerator? = createToneGenerator()
    private var isReleased = false

    fun playStarted() {
        play(ToneGenerator.TONE_PROP_ACK, START_TONE_DURATION_MS)
    }

    fun playStopped() {
        play(ToneGenerator.TONE_PROP_BEEP2, STOP_TONE_DURATION_MS)
    }

    fun release() {
        isReleased = true
        toneGenerator?.release()
        toneGenerator = null
    }

    private fun play(toneType: Int, durationMs: Int) {
        if (isReleased) return
        val generator = toneGenerator ?: createToneGenerator()?.also { toneGenerator = it } ?: return
        try {
            if (!generator.startTone(toneType, durationMs)) {
                Log.w(TAG, "Unable to start recording cue tone=$toneType")
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Unable to play recording cue tone=$toneType", e)
        }
    }

    private fun createToneGenerator(): ToneGenerator? =
        try {
            ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME_PERCENT)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Unable to initialize recording cue player", e)
            null
        }

    private companion object {
        private const val TAG = "RecordingCuePlayer"
        private const val TONE_VOLUME_PERCENT = 80
        private const val START_TONE_DURATION_MS = 150
        private const val STOP_TONE_DURATION_MS = 200
    }
}
