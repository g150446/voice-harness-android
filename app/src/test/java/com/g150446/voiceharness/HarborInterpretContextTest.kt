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
}
