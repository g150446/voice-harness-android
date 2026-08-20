package com.g150446.voiceharness

/**
 * Detects voice commands that clear the multi-turn chat session.
 */
data class ConversationResetParseResult(
    val shouldReset: Boolean,
    /** Remaining user text after stripping a reset phrase; null if the utterance is reset-only. */
    val remainingUserText: String?
)

object ConversationResetDetector {
    private val whitespaceAndLightPunct = Regex("[\\s\\u3000、。,.!！?？　]+")

    /** Whole-utterance patterns (compared after [normalize]). */
    private val fullResetPhrases = listOf(
        "コンテキストをリセットして",
        "コンテキストをリセット",
        "コンテキストリセットして",
        "コンテキストリセット",
        "会話をリセットして",
        "会話をリセット",
        "会話をクリアして",
        "会話をクリア",
        "会話履歴を消して",
        "会話履歴をクリアして",
        "会話履歴をクリア",
        "履歴を消して",
        "履歴をクリアして",
        "履歴をクリア",
        "これまでの会話を忘れて",
        "今までの会話を忘れて",
        "会話を忘れて",
        "最初から会話して",
        "最初から話して",
        "最初から",
        "reset context",
        "reset the context",
        "clear context",
        "clear the context",
        "clear conversation",
        "clear the conversation",
        "forget previous",
        "forget the previous conversation",
        "start over",
        "start over please"
    )

    private val fullResetNormalized: Set<String> =
        fullResetPhrases.map(::normalize).toSet()

    /**
     * Leading reset phrases stripped when followed by more content.
     * Longer phrases first so the most specific match wins.
     */
    private val leadingResetPhrases = listOf(
        "コンテキストをリセットして",
        "コンテキストをリセット",
        "コンテキストリセットして",
        "コンテキストリセット",
        "会話をリセットして",
        "会話をリセット",
        "会話をクリアして",
        "会話をクリア",
        "会話履歴を消して",
        "会話履歴をクリアして",
        "会話履歴をクリア",
        "履歴を消して",
        "履歴をクリアして",
        "履歴をクリア",
        "これまでの会話を忘れて",
        "今までの会話を忘れて",
        "会話を忘れて",
        "最初から会話して",
        "最初から話して",
        "reset the context",
        "reset context",
        "clear the conversation",
        "clear conversation",
        "clear the context",
        "clear context",
        "forget the previous conversation",
        "forget previous",
        "start over please",
        "start over"
    ).sortedByDescending { it.length }

    fun parse(rawText: String): ConversationResetParseResult {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) {
            return ConversationResetParseResult(shouldReset = false, remainingUserText = null)
        }

        val normalizedFull = normalize(trimmed)
        if (normalizedFull in fullResetNormalized) {
            return ConversationResetParseResult(shouldReset = true, remainingUserText = null)
        }

        for (phrase in leadingResetPhrases) {
            val remaining = stripLeadingPhrase(trimmed, phrase) ?: continue
            if (remaining.isEmpty()) {
                return ConversationResetParseResult(shouldReset = true, remainingUserText = null)
            }
            // Avoid treating "最初から" inside unrelated sentences as reset when nothing else matches.
            if (phrase == "最初から" && !looksLikeStandaloneResetLead(trimmed, phrase)) {
                continue
            }
            return ConversationResetParseResult(shouldReset = true, remainingUserText = remaining)
        }

        return ConversationResetParseResult(shouldReset = false, remainingUserText = null)
    }

    fun confirmationMessage(languageCode: String?, remainingUserText: String?): String {
        val japanese = when {
            languageCode?.startsWith("ja", ignoreCase = true) == true -> true
            languageCode?.startsWith("en", ignoreCase = true) == true -> false
            remainingUserText != null ->
                remainingUserText.any { it in '\u3040'..'\u30ff' || it in '\u4e00'..'\u9fff' }
            else -> true
        }
        return if (japanese) {
            "会話の履歴をクリアしました。新しい話題をどうぞ。"
        } else {
            "Conversation history cleared. What would you like to talk about?"
        }
    }

    private fun normalize(text: String): String =
        text.lowercase()
            .replace(whitespaceAndLightPunct, "")
            .trim()

    private fun stripLeadingPhrase(text: String, phrase: String): String? {
        val trimmed = text.trimStart()
        if (trimmed.length < phrase.length) return null
        val head = trimmed.substring(0, phrase.length)
        if (!head.equals(phrase, ignoreCase = true)) return null
        var rest = trimmed.substring(phrase.length)
        rest = rest.replace(Regex("^[\\s\\u3000、。,.!！?？:：;；　]+"), "").trim()
        return rest
    }

    private fun looksLikeStandaloneResetLead(text: String, phrase: String): Boolean {
        val after = text.trimStart().substring(phrase.length)
        return after.isEmpty() || after.firstOrNull()?.let { ch ->
            ch.isWhitespace() || ch in "、。,.!！?？　"
        } == true
    }
}
