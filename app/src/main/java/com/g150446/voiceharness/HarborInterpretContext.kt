package com.g150446.voiceharness

/**
 * Snapshot of the selected Terminal Harbor workspace for Harbor LLM interpretation.
 * Not persisted to voice history (privacy / size).
 */
data class HarborInterpretContext(
    val workspaceId: String,
    val workspaceName: String,
    val agent: String? = null,
    val process: String? = null,
    val workspaceSummary: String? = null,
    val conversation: String = "",
    /** Every switchable workspace, so the interpreter can resolve a switch target itself. */
    val availableWorkspaces: List<String> = emptyList(),
)

internal object HarborContextPrompt {
    private const val MAX_CONTEXT_CHARS = 12_000

    fun systemAppendix(context: HarborInterpretContext?): String {
        if (context == null) return ""
        val body = buildString {
            appendLine("Terminal Harbor context (selected workspace, AI agent session in progress):")
            appendLine("workspace: ${context.workspaceName}")
            context.availableWorkspaces.takeIf { it.isNotEmpty() }?.let {
                appendLine("switchable_workspaces: ${it.joinToString(", ")}")
            }
            context.agent?.takeIf { it.isNotBlank() }?.let { appendLine("agent: $it") }
            context.process?.takeIf { it.isNotBlank() }?.let { appendLine("process: $it") }
            context.workspaceSummary?.takeIf { it.isNotBlank() }?.let {
                appendLine("workspace_summary: $it")
            }
            val conversation = context.conversation.trim()
            if (conversation.isNotEmpty()) {
                appendLine("recent_terminal_conversation:")
                append(conversation)
            }
        }.trim()
        if (body.isEmpty()) return ""
        val clipped = if (body.length <= MAX_CONTEXT_CHARS) {
            body
        } else {
            "…\n" + body.takeLast(MAX_CONTEXT_CHARS)
        }
        return "\n\n$clipped"
    }
}
