package com.g150446.voiceharness

/**
 * Post-ASR filter tuned for quiet user speech priority:
 * drop clear garbage / music hallucinations, keep short Japanese replies.
 */
object AsrTextFilter {
    private val knownHallucinations = setOf(
        "thank you", "thanks", "thank you.", "thanks.",
        "thank you very much", "thank you very much.",
        "you", "bye", "bye.",
        "hmm", "hm", "uh", "um", "ah", "oh",
        "the", "a", "i", "ok", "okay",
        "字幕", "字幕by", "ご視聴ありがとうございました",
        "ご静聴ありがとうございました"
    )

    private val cjkRegex = Regex("[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff]")
    private val digitsAndPunctOnly = Regex("^[\\d\\s.,!?？！。、・\\-_/\\\\:：;；'\"“”‘’…·]+$")
    private val singleLatin = Regex("^[A-Za-z]$")
    private val shortLatinNoise = Regex("^[A-Za-z]{1,2}$")

    fun isGarbageOrEmpty(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true

        val normalized = trimmed
            .lowercase()
            .replace(Regex("[.!?。、，,]+$"), "")
            .trim()
        if (normalized.isEmpty()) return true
        if (normalized in knownHallucinations) return true

        // Background music often yields bare digits / punctuation ("3", "23").
        if (digitsAndPunctOnly.matches(trimmed)) return true
        if (singleLatin.matches(trimmed)) return true

        val hasCjk = cjkRegex.containsMatchIn(trimmed)
        if (hasCjk) {
            // Quiet Japanese replies like 「はい」「うん」 must pass.
            val meaningful = trimmed.count { !it.isWhitespace() }
            return meaningful == 0
        }

        // Latin: require at least 3 letters so "ok"/"hi" noise is dropped but real phrases pass.
        val letters = trimmed.count { it.isLetter() }
        if (letters < 3) return true
        if (shortLatinNoise.matches(normalized)) return true
        return false
    }
}
