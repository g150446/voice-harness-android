package com.g150446.voiceharness

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicReference

/**
 * Shares loaded backends so the same local model is not double-loaded when selected
 * for both STT and LLM.
 */
internal class BackendRegistry(private val appContext: Context) {
    private val lock = Any()
    private val gemma = AtomicReference<GemmaOnDeviceBackend?>(null)
    private val qwen = AtomicReference<QwenOnDeviceBackend?>(null)
    private val groq = AtomicReference<GroqVoiceAiBackend?>(null)
    private val openRouter = AtomicReference<OpenRouterLlmBackend?>(null)

    fun obtainGemma(): GemmaOnDeviceBackend = synchronized(lock) {
        gemma.get() ?: GemmaOnDeviceBackend(appContext).also { gemma.set(it) }
    }

    fun obtainQwen(): QwenOnDeviceBackend = synchronized(lock) {
        qwen.get() ?: QwenOnDeviceBackend(appContext).also { qwen.set(it) }
    }

    fun obtainGroq(): GroqVoiceAiBackend = synchronized(lock) {
        groq.get() ?: GroqVoiceAiBackend(appContext).also { groq.set(it) }
    }

    fun obtainOpenRouter(): OpenRouterLlmBackend = synchronized(lock) {
        openRouter.get() ?: OpenRouterLlmBackend(appContext).also { openRouter.set(it) }
    }

    fun releaseUnused(stt: SttBackendId, llm: LlmBackendId) {
        synchronized(lock) {
            val needGemma = stt == SttBackendId.GEMMA || llm == LlmBackendId.GEMMA
            val needQwen = stt == SttBackendId.QWEN || llm == LlmBackendId.QWEN
            val needGroq = stt == SttBackendId.GROQ || llm == LlmBackendId.GROQ
            val needOpenRouter = llm == LlmBackendId.OPENROUTER
            if (!needGemma) releaseVoice(gemma, "Gemma")
            if (!needQwen) releaseVoice(qwen, "Qwen")
            if (!needGroq) releaseVoice(groq, "Groq")
            if (!needOpenRouter) {
                openRouter.getAndSet(null)?.let {
                    try {
                        it.release()
                        Log.d(TAG, "Released OpenRouter backend")
                    } catch (e: Exception) {
                        Log.w(TAG, "Release OpenRouter failed: ${e.message}")
                    }
                }
            }
        }
    }

    fun releaseAll() {
        synchronized(lock) {
            releaseVoice(gemma, "Gemma")
            releaseVoice(qwen, "Qwen")
            releaseVoice(groq, "Groq")
            openRouter.getAndSet(null)?.release()
        }
    }

    fun cancelOpenRouter() {
        openRouter.get()?.cancel()
    }

    private fun <T : VoiceAiBackend> releaseVoice(ref: AtomicReference<T?>, label: String) {
        ref.getAndSet(null)?.let {
            try {
                it.release()
                Log.d(TAG, "Released $label backend")
            } catch (e: Exception) {
                Log.w(TAG, "Release $label failed: ${e.message}")
            }
        }
    }

    private companion object {
        private const val TAG = "BackendRegistry"
    }
}
