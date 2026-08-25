package com.g150446.voiceharness

/** Builds ephemeral prompt fragments from transient screen context. */
object ScreenContextPrompt {
    private const val MAX_TEXT_CHARS = 24_000

    fun systemAppendix(screen: ScreenContext?): String {
        if (screen == null || !screen.hasText) return ""
        val text = screen.assistText!!.trim().take(MAX_TEXT_CHARS)
        if (text.isEmpty()) return ""
        val source = buildString {
            screen.sourcePackage?.takeIf { it.isNotBlank() }?.let { append("Source app: $it. ") }
            screen.sourceUri?.takeIf { it.isNotBlank() }?.let { append("Source URI: $it. ") }
        }
        return " The user is looking at an on-screen context. $source" +
            "Use it only when relevant to the current question. " +
            "Do not invent UI that is not present. Screen text:\n$text"
    }

    fun truncateAssistText(raw: String, maxChars: Int = MAX_TEXT_CHARS): String =
        raw.trim().take(maxChars)
}
