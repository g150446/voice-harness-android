package com.g150446.voiceharness

/** The question is cut short so page 1 is not spent on it; the reply gets the room. */
internal const val OPENCLAW_G2_MAX_USER_CHARS = 120
/** Safety cap on a reply; the plugin pages a long reply, so this only bounds runaway output. */
internal const val OPENCLAW_G2_MAX_REPLY_CHARS = 3_000

private const val USER_LABEL = "あなた"
private const val ASSISTANT_LABEL = "OpenClaw"
private const val THINKING_BODY = "考え中…"

/**
 * Renders the newest exchange for the Even G2 screen: the last message we sent and OpenClaw's
 * (final) reply to it, nothing older. Returns null when there is nothing to show.
 */
internal fun openClawConversationG2Text(messages: List<OpenClawHistoryMessage>): String? {
    val turn = lastTurn(messages) ?: return null
    val question = turn.firstOrNull { it.role == "user" }
    // The final assistant message is the reply; earlier ones are interim narration, and the live
    // reply handed to showNow is also just the final message, so the two renderings agree.
    val reply = turn.lastOrNull { it.role != "user" }?.text
    return openClawExchangeG2Text(question?.text, reply)
}

/** Same layout as [openClawConversationG2Text] while the reply is still being generated. */
internal fun openClawPendingG2Text(user: String): String =
    openClawExchangeG2Text(user, THINKING_BODY) ?: "$ASSISTANT_LABEL: $THINKING_BODY"

private fun openClawExchangeG2Text(user: String?, reply: String?): String? {
    val blocks = ArrayList<String>(2)
    user?.takeIf(String::isNotBlank)?.let {
        blocks += "$USER_LABEL: ${truncate(it, OPENCLAW_G2_MAX_USER_CHARS)}"
    }
    reply?.takeIf(String::isNotBlank)?.let {
        blocks += "$ASSISTANT_LABEL: ${truncate(it, OPENCLAW_G2_MAX_REPLY_CHARS)}"
    }
    return blocks.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
}

/**
 * The Gateway may not have persisted the turn that was just answered when the history is read.
 * Appends the known question/reply so the glass never shows a conversation missing its own reply;
 * the next poll replaces it with the Gateway's version.
 */
internal fun mergeLatestExchange(
    history: List<OpenClawHistoryMessage>,
    user: String?,
    reply: String?,
): List<OpenClawHistoryMessage> {
    val replyText = OpenClawHistoryParser.sanitize(reply.orEmpty())
    if (replyText.isEmpty()) return history
    if (history.lastOrNull()?.let { it.role == "assistant" && it.text.trim() == replyText } == true) {
        return history
    }
    val userText = user?.trim().orEmpty()
    val lastUser = history.lastOrNull { it.role == "user" }?.text?.trim()
    return buildList {
        addAll(history)
        if (userText.isNotEmpty() && lastUser != userText) add(OpenClawHistoryMessage("user", userText))
        add(OpenClawHistoryMessage("assistant", replyText))
    }
}

/** The last turn: the newest user message with the assistant messages after it. */
private fun lastTurn(messages: List<OpenClawHistoryMessage>): List<OpenClawHistoryMessage>? {
    var turn: MutableList<OpenClawHistoryMessage>? = null
    for (message in messages) {
        if (message.text.isBlank()) continue
        if (message.role == "user" || turn == null) {
            turn = mutableListOf(message)
        } else {
            turn += message
        }
    }
    return turn
}

private fun truncate(text: String, max: Int): String {
    val normalized = text.trim()
    if (normalized.length <= max) return normalized
    var end = max
    if (Character.isHighSurrogate(normalized[end - 1])) end -= 1
    return normalized.substring(0, end).trimEnd() + "…"
}
