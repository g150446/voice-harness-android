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
 * Plays recording start/stop cues on the media stream (same path as TTS).
 *
 * Note: USAGE_ASSISTANCE_SONIFICATION maps to SYSTEM/NOTIFICATION, which are often
 * muted in silent/vibrate mode — cues were "playing" but inaudible while TTS worked.
 */
internal class RecordingCuePlayer(context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val activeTracks = mutableSetOf<AudioTrack>()
    private var isReleased = false

    fun playStarted() {
        playSequence(START_TONES)
    }

    fun playStopped() {
        playSequence(STOP_TONES)
    }

    fun release() {
        isReleased = true
        handler.removeCallbacksAndMessages(null)
        activeTracks.toList().forEach(::releaseTrack)
    }

    private fun playSequence(tones: List<ToneSpec>) {
        if (isReleased || tones.isEmpty()) return
        val samples = createSequenceSamples(tones)
        val durationMs = tones.sumOf { it.durationMs + it.gapMs } - tones.last().gapMs
        playSamples(samples, durationMs.coerceAtLeast(1))
    }

    private fun playSamples(samples: ShortArray, durationMs: Int) {
        if (isReleased) return

        val speaker = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

        var track: AudioTrack? = null
        try {
            // Match TTS routing (USAGE_MEDIA → STREAM_MUSIC). Sonification streams are
            // frequently muted while media volume remains audible.
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(TONE_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes((samples.size * Short.SIZE_BYTES).coerceAtLeast(
                    AudioTrack.getMinBufferSize(
                        TONE_SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                    )
                ))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
                .build()

            if (speaker != null) {
                if (!track.setPreferredDevice(speaker)) {
                    Log.w(TAG, "Preferred speaker failed; using default media route")
                }
            }

            track.setVolume(1.0f)

            val written = track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            if (written != samples.size) {
                Log.w(TAG, "Incomplete cue write: $written/${samples.size}")
                track.release()
                return
            }

            activeTracks += track
            val playResult = track.play()
            Log.i(
                TAG,
                "Cue play result=$playResult state=${track.playState} " +
                    "frames=${track.bufferSizeInFrames} durationMs=$durationMs",
            )
            val playingTrack = track
            handler.postDelayed(
                { releaseTrack(playingTrack) },
                durationMs + RELEASE_GRACE_MS
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "Unable to play recording cue", e)
            track?.let(::releaseTrack)
        }
    }

    private fun releaseTrack(track: AudioTrack) {
        if (!activeTracks.remove(track) && isReleased) {
            return
        }
        try {
            track.stop()
        } catch (_: IllegalStateException) {
        }
        track.release()
    }

    private fun createSequenceSamples(tones: List<ToneSpec>): ShortArray {
        val totalSamples = tones.sumOf { tone ->
            val toneSamples = TONE_SAMPLE_RATE * tone.durationMs / 1_000
            val gapSamples = TONE_SAMPLE_RATE * tone.gapMs / 1_000
            toneSamples + gapSamples
        }.let { total ->
            val lastGap = TONE_SAMPLE_RATE * tones.last().gapMs / 1_000
            (total - lastGap).coerceAtLeast(1)
        }

        val out = ShortArray(totalSamples)
        var offset = 0
        tones.forEachIndexed { index, tone ->
            val toneSamples = createToneSamples(tone.frequencyHz, tone.durationMs)
            toneSamples.copyInto(out, destinationOffset = offset)
            offset += toneSamples.size
            if (index < tones.lastIndex && tone.gapMs > 0) {
                offset += TONE_SAMPLE_RATE * tone.gapMs / 1_000
            }
        }
        return out
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

    private data class ToneSpec(
        val frequencyHz: Double,
        val durationMs: Int,
        val gapMs: Int = 0,
    )

    private companion object {
        private const val TAG = "RecordingCuePlayer"
        // Match primary mixer rate on modern devices (TTS path uses ~48k).
        private const val TONE_SAMPLE_RATE = 48_000
        private const val TONE_AMPLITUDE = 0.65
        private const val FADE_DURATION_MS = 8
        private const val RELEASE_GRACE_MS = 300L

        private val START_TONES = listOf(
            ToneSpec(frequencyHz = 880.0, durationMs = 100, gapMs = 50),
            ToneSpec(frequencyHz = 1175.0, durationMs = 130, gapMs = 0),
        )

        private val STOP_TONES = listOf(
            ToneSpec(frequencyHz = 780.0, durationMs = 100, gapMs = 50),
            ToneSpec(frequencyHz = 520.0, durationMs = 150, gapMs = 0),
        )
    }
}
