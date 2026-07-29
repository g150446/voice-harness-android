package com.g150446.voiceharness

/**
 * User-selected base language for ASR prompting (Gemma path).
 * Does not change Chat system prompts.
 */
enum class SpeechBaseLanguage {
    /** Japanese primary; occasional English words/loanwords allowed. */
    JAPANESE,

    /** English primary. */
    ENGLISH,

    /** Japanese or English; follow the spoken language. */
    AUTO;

    val displayName: String
        get() = when (this) {
            JAPANESE -> "日本語（英単語混在可）"
            ENGLISH -> "英語"
            AUTO -> "自動（日/英）"
        }

    val description: String
        get() = when (this) {
            JAPANESE -> "日本語を基本に書き起こします。英単語や外来語が混ざる場合はそのまま残します。"
            ENGLISH -> "英語を基本に書き起こします。"
            AUTO -> "日本語または英語を自動判定して書き起こします。"
        }

    /** Hint for downstream TTS / language resolver when ASR does not return a code. */
    val languageCodeHint: String?
        get() = when (this) {
            JAPANESE -> "ja"
            ENGLISH -> "en"
            AUTO -> null
        }

    companion object {
        fun fromStorage(value: String?): SpeechBaseLanguage =
            entries.firstOrNull { it.name == value } ?: JAPANESE
    }
}
