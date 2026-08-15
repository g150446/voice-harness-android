package com.g150446.voiceharness

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin

/**
 * Plays recording cues on the phone speaker so they never start an A2DP stream while BLE audio
 * is being received. If the built-in speaker cannot be selected, the cue is skipped rather than
 * falling back to a Bluetooth output.
 */
internal class RecordingCuePlayer(context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val activeTracks = mutableSetOf<AudioTrack>()
    private var isReleased = false

    fun playStarted() {
        play(START_TONE_FREQUENCY_HZ, START_TONE_DURATION_MS)
    }

    fun playStopped() {
        play(STOP_TONE_FREQUENCY_HZ, STOP_TONE_DURATION_MS)
    }

    fun release() {
        isReleased = true
        handler.removeCallbacksAndMessages(null)
        activeTracks.toList().forEach(::releaseTrack)
    }

    private fun play(frequencyHz: Double, durationMs: Int) {
        if (isReleased) return

        val speaker = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        if (speaker == null) {
            Log.w(TAG, "Built-in speaker unavailable; skipping recording cue")
            return
        }

        val samples = createToneSamples(frequencyHz, durationMs)
        var track: AudioTrack? = null
        try {
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(TONE_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(samples.size * Short.SIZE_BYTES)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            if (!track.setPreferredDevice(speaker)) {
                Log.w(TAG, "Unable to route recording cue to built-in speaker; skipping")
                track.release()
                return
            }

            val written = track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            if (written != samples.size) {
                Log.w(TAG, "Unable to write complete recording cue: $written/${samples.size}")
                track.release()
                return
            }

            activeTracks += track
            track.play()
            val playingTrack = track
            handler.postDelayed(
                { releaseTrack(playingTrack) },
                durationMs + RELEASE_GRACE_MS
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "Unable to play speaker-routed recording cue", e)
            track?.let(::releaseTrack)
        }
    }

    private fun releaseTrack(track: AudioTrack) {
        if (!activeTracks.remove(track) && isReleased) {
            // release() may race a delayed release callback; AudioTrack.release() is idempotent in
            // practice, but avoid calling it twice.
            return
        }
        try {
            track.stop()
        } catch (_: IllegalStateException) {
        }
        track.release()
    }

    private fun createToneSamples(frequencyHz: Double, durationMs: Int): ShortArray {
        val sampleCount = TONE_SAMPLE_RATE * durationMs / 1_000
        val fadeSamples = (TONE_SAMPLE_RATE * FADE_DURATION_MS / 1_000)
            .coerceAtMost(sampleCount / 2)
        return ShortArray(sampleCount) { index ->
            val fade = when {
                index < fadeSamples -> index.toDouble() / fadeSamples.coerceAtLeast(1)
                index >= sampleCount - fadeSamples ->
                    (sampleCount - index - 1).toDouble() / fadeSamples.coerceAtLeast(1)
                else -> 1.0
            }
            val phase = 2.0 * PI * frequencyHz * index / TONE_SAMPLE_RATE
            (sin(phase) * Short.MAX_VALUE * TONE_AMPLITUDE * fade).toInt().toShort()
        }
    }

    private companion object {
        private const val TAG = "RecordingCuePlayer"
        private const val TONE_SAMPLE_RATE = 16_000
        private const val TONE_AMPLITUDE = 0.35
        private const val START_TONE_FREQUENCY_HZ = 880.0
        private const val STOP_TONE_FREQUENCY_HZ = 660.0
        private const val START_TONE_DURATION_MS = 150
        private const val STOP_TONE_DURATION_MS = 200
        private const val FADE_DURATION_MS = 8
        private const val RELEASE_GRACE_MS = 250L
    }
}
