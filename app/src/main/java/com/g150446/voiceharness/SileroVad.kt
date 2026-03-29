package com.g150446.voiceharness

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.Closeable
import java.nio.FloatBuffer
import java.nio.LongBuffer

private const val TAG = "SileroVad"

/**
 * Silero VAD v4 wrapper using ONNX Runtime.
 *
 * Model I/O (16 kHz):
 *   input  : Float32 [1, 512]     — normalized PCM samples (-1..1)
 *   h      : Float32 [2, 1, 64]   — RNN hidden state (zeros on reset)
 *   c      : Float32 [2, 1, 64]   — RNN cell state   (zeros on reset)
 *   sr     : Int64   [1]          — sample rate (16000)
 *   output : Float32 [1, 1]       — speech probability 0..1
 *   hn     : Float32 [2, 1, 64]   — updated hidden state
 *   cn     : Float32 [2, 1, 64]   — updated cell state
 *
 * The RNN state must be carried across frames within one recording session.
 * Call reset() at the start of each new recording.
 */
class SileroVad(context: Context) : Closeable {

    companion object {
        const val FRAME_SIZE = 512
        private const val SAMPLE_RATE = 16_000L
        private val STATE_SHAPE = longArrayOf(2, 1, 64)
        private val STATE_SIZE  = (2 * 1 * 64)
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    private var h = FloatArray(STATE_SIZE)
    private var c = FloatArray(STATE_SIZE)

    init {
        val bytes = context.assets.open("silero_vad.onnx").readBytes()
        session = env.createSession(bytes, OrtSession.SessionOptions())
        Log.d(TAG, "Silero VAD session created")
    }

    /** Reset RNN state. Call once before processing each new recording. */
    fun reset() {
        h = FloatArray(STATE_SIZE)
        c = FloatArray(STATE_SIZE)
    }

    /**
     * Predict speech probability for one frame.
     * @param samples FloatArray of exactly [FRAME_SIZE] normalized PCM values (-1..1)
     * @return speech probability in 0..1
     */
    fun predict(samples: FloatArray): Float {
        require(samples.size == FRAME_SIZE) {
            "Expected $FRAME_SIZE samples, got ${samples.size}"
        }

        val inputTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(samples), longArrayOf(1, FRAME_SIZE.toLong())
        )
        val hTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(h), STATE_SHAPE)
        val cTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(c), STATE_SHAPE)
        val srTensor = OnnxTensor.createTensor(
            env, LongBuffer.wrap(longArrayOf(SAMPLE_RATE)), longArrayOf(1)
        )

        val inputs = mapOf(
            "input" to inputTensor,
            "h"     to hTensor,
            "c"     to cTensor,
            "sr"    to srTensor
        )

        return session.run(inputs).use { result ->
            val prob = (result[0].value as Array<*>).let {
                (it[0] as FloatArray)[0]
            }

            // Update RNN state for next frame
            h = flattenState(result["hn"].get().value)
            c = flattenState(result["cn"].get().value)

            inputTensor.close(); hTensor.close(); cTensor.close(); srTensor.close()
            prob
        }
    }

    /** Flatten [2][1][64] float[][][] → FloatArray(128) */
    @Suppress("UNCHECKED_CAST")
    private fun flattenState(value: Any?): FloatArray {
        val arr = value as Array<Array<FloatArray>>
        val out = FloatArray(STATE_SIZE)
        var idx = 0
        for (i in arr) for (j in i) for (v in j) out[idx++] = v
        return out
    }

    override fun close() {
        session.close()
        Log.d(TAG, "Silero VAD session closed")
    }
}
