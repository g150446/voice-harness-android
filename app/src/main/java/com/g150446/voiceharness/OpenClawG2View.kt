package com.g150446.voiceharness

/** Turns kept on the glass, newest first. Older ones are dropped, not paged. */
internal const val OPENCLAW_G2_MAX_TURNS = 4
/** Total body budget; keeps the plugin at a handful of single-tap pages. */
internal const val OPENCLAW_G2_MAX_CHARS = 1_200
/** A single message is cut here so one long reply cannot push everything else out. */
internal const val OPENCLAW_G2_MAX_MESSAGE_CHARS = 500

private const val USER_LABEL = "あなた"
private const val ASSISTANT_LABEL = "OpenClaw"

/**
 * Renders the OpenClaw session transcript for the Even G2 screen: the newest exchange first
 * (page 1), older exchanges on later pages. Returns null when there is nothing to show.
 */
internal fun openClawConversationG2Text(messages: List<OpenClawHistoryMessage>): String? {
    val turns = groupTurns(messages)
    if (turns.isEmpty()) return null
    val blocks = ArrayList<String>()
    var total = 0
    for (turn in turns.asReversed().take(OPENCLAW_G2_MAX_TURNS)) {
        val block = turn.joinToString("\n") { message ->
            val label = if (message.role == "user") USER_LABEL else ASSISTANT_LABEL
            "$label: ${truncate(message.text, OPENCLAW_G2_MAX_MESSAGE_CHARS)}"
        }
        // The newest turn is always shown; older ones only while they fit.
        if (blocks.isNotEmpty() && total + block.length > OPENCLAW_G2_MAX_CHARS) break
        blocks += block
        total += block.length
    }
    return blocks.joinToString("\n\n")
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

/** A turn starts at each user message; assistant messages attach to the turn before them. */
private fun groupTurns(messages: List<OpenClawHistoryMessage>): List<List<OpenClawHistoryMessage>> {
    val turns = ArrayList<MutableList<OpenClawHistoryMessage>>()
    for (message in messages) {
        if (message.text.isBlank()) continue
        if (message.role == "user" || turns.isEmpty()) {
            turns += mutableListOf(message)
        } else {
            turns.last() += message
        }
    }
    return turns
}

private fun truncate(text: String, max: Int): String {
    val normalized = text.trim()
    if (normalized.length <= max) return normalized
    var end = max
    if (Character.isHighSurrogate(normalized[end - 1])) end -= 1
    return normalized.substring(0, end).trimEnd() + "…"
}
