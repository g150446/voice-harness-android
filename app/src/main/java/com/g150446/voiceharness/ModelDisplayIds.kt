package com.g150446.voiceharness

import android.content.Context

/** Resolves human-readable ASR/LLM model IDs for settings UI and history. */
internal object ModelDisplayIds {
    fun sttModelId(context: Context, backend: SttBackendId = ModelManager.currentSttBackend(context)): String =
        when (backend) {
            SttBackendId.GROQ -> GroqVoiceAiBackend.WHISPER_MODEL
            SttBackendId.GEMMA -> ModelManager.status.value.gemma.fileName ?: "gemma"
            SttBackendId.QWEN -> ModelManager.status.value.qwenAsrDecoder.fileName ?: "qwen-asr"
        }

    fun llmModelId(context: Context, backend: LlmBackendId = ModelManager.currentLlmBackend(context)): String =
        when (backend) {
            LlmBackendId.GROQ -> GroqChatRequestBuilder.CHAT_MODEL
            LlmBackendId.OPENROUTER ->
                OpenRouterPrefs.getModelId(context).ifBlank { "未選択" }
            LlmBackendId.GEMMA -> ModelManager.status.value.gemma.fileName ?: "gemma"
            LlmBackendId.QWEN -> ModelManager.status.value.lfmChat.fileName ?: "lfm-chat"
        }

    fun sttLabel(context: Context): String {
        val backend = ModelManager.currentSttBackend(context)
        return "${backend.displayName} / ${sttModelId(context, backend)}"
    }

    fun llmLabel(context: Context): String {
        val backend = ModelManager.currentLlmBackend(context)
        return "${backend.displayName} / ${llmModelId(context, backend)}"
    }
}
