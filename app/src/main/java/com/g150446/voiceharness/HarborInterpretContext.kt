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

    /**
     * Only the scrollback is clipped, and only from its start.
     *
     * The header is what names the workspace, the agent and every switch target, and it is a
     * few hundred characters against a scrollback of tens of thousands. Clipping the joined
     * body from the end — which is what this did — dropped the header in every workspace with
     * a running agent, leaving the interpreter unable to name a workspace to switch to.
     */
    fun systemAppendix(context: HarborInterpretContext?): String {
        if (context == null) return ""
        val header = buildString {
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
        }
        val conversation = context.conversation.trim()
        val label = "recent_terminal_conversation:\n"
        val budget = MAX_CONTEXT_CHARS - header.length - label.length
        val body = when {
            conversation.isEmpty() || budget <= 0 -> header.trim()
            conversation.length <= budget -> header + label + conversation
            else -> header + label + "…\n" + conversation.takeLast(budget)
        }
        if (body.isEmpty()) return ""
        return "\n\n$body"
    }
}
