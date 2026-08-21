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

    /** Compact (no punctuation/space) Japanese dumps typical of silence/noise ASR. */
    private val compactPolitenessHallucinations = setOf(
        "はいありがとうございます",
        "はいありがとうございました",
        "ありがとうございます",
        "ありがとうございました",
        "お疲れ様です",
        "お疲れさまでした",
        "ご視聴ありがとうございました",
        "ご静聴ありがとうございました"
    )

    private val cjkRegex = Regex("[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff]")
    private val digitsAndPunctOnly = Regex("^[\\d\\s.,!?？！。、・\\-_/\\\\:：;；'\"“”‘’…·]+$")
    private val singleLatin = Regex("^[A-Za-z]$")
    private val shortLatinNoise = Regex("^[A-Za-z]{1,2}$")
    private val punctOrSpace =
        Regex("[\\s\\u3000.,!?？！。、，・\\-_/\\\\:：;；'\"“”‘’…·]+")
    private val leadingPunctOrSpace =
        Regex("^[\\s\\u3000.,!?？！。、，・\\-_/\\\\:：;；'\"“”‘’…·]+")

    fun isGarbageOrEmpty(
        text: String,
        vocabulary: List<AsrVocabularyTerm> = AsrVocabularyCatalog.builtIn
    ): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true

        val normalized = trimmed
            .lowercase()
            .replace(Regex("[.!?。、，,]+$"), "")
            .trim()
        if (normalized.isEmpty()) return true
        if (normalized in knownHallucinations) return true
        if (isPolitenessHallucination(trimmed)) return true

        // Background music often yields bare digits / punctuation ("3", "23").
        if (digitsAndPunctOnly.matches(trimmed)) return true
        if (singleLatin.matches(trimmed)) return true

        if (isVocabularyEchoWithoutTrigger(trimmed, vocabulary)) return true

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

    /**
     * Japanese models often emit a courtesy phrase on noise, like Whisper's "thank you".
     * Keep a lone 「はい」「うん」「ありがとう」; drop 「はい、ありがとうございます」 dumps.
     */
    internal fun isPolitenessHallucination(text: String): Boolean {
        val compact = compactJapanese(text)
        if (compact.isEmpty()) return false
        if (compact in compactPolitenessHallucinations) return true
        val hasHai = compact.contains("はい")
        val hasThanks = compact.contains("ありがとう")
        if (hasHai && hasThanks) {
            val remainder = compact
                .replace("はい", "")
                .replace("ありがとうございました", "")
                .replace("ありがとうございます", "")
                .replace("ありがとう", "")
            return remainder.isEmpty()
        }
        return false
    }

    private fun compactJapanese(text: String): String =
        text.lowercase().replace(punctOrSpace, "")

    /**
     * Prompt-echo dump such as 「ちいかわ、ハチワレ、うさぎ」 with no アニメ/anime.
     * A single vocabulary word, or a name plus other content, is kept.
     */
    fun isVocabularyEchoWithoutTrigger(
        text: String,
        vocabulary: List<AsrVocabularyTerm> = AsrVocabularyCatalog.builtIn
    ): Boolean {
        if (AsrVocabularyCatalog.containsTrigger(text, vocabulary)) return false
        return isVocabularyOnlyDump(text, vocabulary)
    }

    internal fun isVocabularyOnlyDump(
        text: String,
        vocabulary: List<AsrVocabularyTerm>
    ): Boolean {
        val forms = vocabulary.map { it.writtenForm.trim() }
            .filter { it.isNotEmpty() }
            .sortedByDescending { it.length }
        if (forms.size < 2) return false

        var rest = text.trim()
        val matched = LinkedHashSet<String>()
        while (rest.isNotEmpty()) {
            rest = rest.replaceFirst(leadingPunctOrSpace, "")
            if (rest.isEmpty()) break
            val hit = forms.firstOrNull { rest.startsWith(it) } ?: return false
            matched += hit
            rest = rest.substring(hit.length)
        }
        return matched.size >= 2
    }
}
