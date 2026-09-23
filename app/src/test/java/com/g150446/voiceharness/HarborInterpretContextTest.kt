package com.g150446.voiceharness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HarborInterpretContextTest {
    @Test
    fun `system appendix includes agent and conversation`() {
        val appendix = HarborContextPrompt.systemAppendix(
            HarborInterpretContext(
                workspaceId = "ws-1",
                workspaceName = "voice-harness-even-g2",
                agent = "codex",
                process = "node",
                workspaceSummary = "editing Harbor flow",
                conversation = "User: commit\nAgent: Ready to push?",
            )
        )
        assertTrue(appendix.contains("voice-harness-even-g2"))
        assertTrue(appendix.contains("agent: codex"))
        assertTrue(appendix.contains("Ready to push?"))
        assertTrue(appendix.contains("AI agent session in progress"))
    }

    @Test
    fun `system appendix is empty for null context`() {
        assertEquals("", HarborContextPrompt.systemAppendix(null))
    }

    @Test
    fun `system appendix truncates very long conversation`() {
        val long = "a".repeat(20_000)
        val appendix = HarborContextPrompt.systemAppendix(
            HarborInterpretContext(
                workspaceId = "ws",
                workspaceName = "demo",
                conversation = long,
            )
        )
        assertTrue(appendix.length < 20_000)
        assertTrue(appendix.contains("…"))
        assertFalse(appendix.contains("a".repeat(15_000)))
    }

    /**
     * The scrollback is what overflows, so it is what gets cut. Clipping the whole body from
     * the end instead took the header with it — and the header is where the switch targets are,
     * which is how 「〜に切り替えて」 stopped resolving to a workspace.
     */
    @Test
    fun `truncation keeps the header a switch needs`() {
        val appendix = HarborContextPrompt.systemAppendix(
            HarborInterpretContext(
                workspaceId = "ws",
                workspaceName = "demo",
                agent = "claude",
                conversation = "a".repeat(20_000),
                availableWorkspaces = listOf("demo", "terminal-harbor"),
            )
        )
        assertTrue(appendix.contains("workspace: demo"))
        assertTrue(appendix.contains("switchable_workspaces: demo, terminal-harbor"))
        assertTrue(appendix.contains("agent: claude"))
        assertTrue(appendix.contains("recent_terminal_conversation:"))
    }
}
