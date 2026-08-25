package com.g150446.voiceharness

enum class SttBackendId {
    GEMMA,
    QWEN,
    GROQ;

    val displayName: String
        get() = when (this) {
            GEMMA -> "Gemma 4 E2B"
            QWEN -> "Qwen3-ASR"
            GROQ -> "Cloud (Groq Whisper)"
        }

    val isCloud: Boolean get() = this == GROQ

    companion object {
        fun fromStorage(value: String?): SttBackendId =
            entries.firstOrNull { it.name == value } ?: GEMMA

        fun fromProfile(profile: OnDeviceProfile): SttBackendId = when (profile) {
            OnDeviceProfile.GEMMA -> GEMMA
            OnDeviceProfile.QWEN -> QWEN
            OnDeviceProfile.GROQ -> GROQ
        }
    }
}

enum class LlmBackendId {
    GEMMA,
    QWEN,
    GROQ,
    OPENROUTER;

    val displayName: String
        get() = when (this) {
            GEMMA -> "Gemma 4 E2B"
            QWEN -> "LFM 2.5"
            GROQ -> "Cloud (Groq Chat)"
            OPENROUTER -> "OpenRouter"
        }

    val isCloud: Boolean get() = this == GROQ || this == OPENROUTER

    companion object {
        fun fromStorage(value: String?): LlmBackendId =
            entries.firstOrNull { it.name == value } ?: GEMMA

        fun fromProfile(profile: OnDeviceProfile): LlmBackendId = when (profile) {
            OnDeviceProfile.GEMMA -> GEMMA
            OnDeviceProfile.QWEN -> QWEN
            OnDeviceProfile.GROQ -> GROQ
        }
    }
}
