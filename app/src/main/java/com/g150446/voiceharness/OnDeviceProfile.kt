package com.g150446.voiceharness

enum class OnDeviceProfile {
    /** Qwen3-ASR + LFM 2.5 2.6B (LEAP) */
    QWEN,

    /** Quality path: Gemma 4 E2B multimodal ASR + chat */
    GEMMA;

    val displayName: String
        get() = when (this) {
            QWEN -> "Qwen ASR + LFM 2.5"
            GEMMA -> "高品質 (Gemma 4 E2B)"
        }

    companion object {
        fun fromStorage(value: String?): OnDeviceProfile =
            entries.firstOrNull { it.name == value } ?: GEMMA
    }
}
