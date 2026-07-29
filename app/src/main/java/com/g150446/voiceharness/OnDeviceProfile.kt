package com.g150446.voiceharness

enum class OnDeviceProfile {
    /** Fast path: Qwen3-ASR + Qwen3.5-0.8B */
    QWEN,

    /** Quality path: Gemma 4 E2B multimodal ASR + chat */
    GEMMA;

    val displayName: String
        get() = when (this) {
            QWEN -> "高速 (Qwen)"
            GEMMA -> "高品質 (Gemma 4 E2B)"
        }

    companion object {
        fun fromStorage(value: String?): OnDeviceProfile =
            entries.firstOrNull { it.name == value } ?: GEMMA
    }
}
