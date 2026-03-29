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
 * Silero VAD v5 wrapper using ONNX Runtime.
 *
 * Model I/O (16 kHz):
 *   input  : Float32 [1, 512]     — normalized PCM samples (-1..1)
 *   state  : Float32 [2, 1, 128]  — RNN state (h and c merged; zeros on reset)
 *   sr     : Int64   []           — sample rate scalar (16000)
 *   output : Float32 [1, 1]       — speech probability 0..1
 *   stateN : Float32 [2, 1, 128]  — updated RNN state
 *
 * The RNN state must be carried across frames within one recording session.
 * Call reset() at the start of each new recording.
 */
class SileroVad(context: Context) : Closeable {

    companion object {
        const val FRAME_SIZE = 512
        private const val SAMPLE_RATE = 16_000L
        private val STATE_SHAPE = longArrayOf(2, 1, 128)
        private const val STATE_SIZE = 2 * 1 * 128
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    private var state = FloatArray(STATE_SIZE)

    init {
        val bytes = context.assets.open("silero_vad.onnx").readBytes()
        session = env.createSession(bytes, OrtSession.SessionOptions())
        Log.d(TAG, "Silero VAD v5 session created")
    }

    /** Reset RNN state. Call once before processing each new recording. */
    fun reset() {
        state = FloatArray(STATE_SIZE)
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
        val stateTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(state), STATE_SHAPE
        )
        // sr is a scalar in v5 — shape is empty longArrayOf()
        val srTensor = OnnxTensor.createTensor(
            env, LongBuffer.wrap(longArrayOf(SAMPLE_RATE)), longArrayOf()
        )

        val inputs = mapOf(
            "input" to inputTensor,
            "state" to stateTensor,
            "sr"    to srTensor
        )

        return session.run(inputs).use { result ->
            val prob = (result[0].value as Array<*>).let {
                (it[0] as FloatArray)[0]
            }

            // Carry updated state to next frame
            state = flattenState(result["stateN"].get().value)

            inputTensor.close(); stateTensor.close(); srTensor.close()
            prob
        }
    }

    /** Flatten float[][][] → FloatArray */
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
