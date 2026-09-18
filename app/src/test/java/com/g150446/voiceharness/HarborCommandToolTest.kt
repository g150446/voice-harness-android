package com.g150446.voiceharness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HarborCommandToolTest {
    @Test
    fun `tool definition names harbor_command`() {
        val def = HarborCommandTool.toolDefinitionJson()
        assertEquals("function", def.getString("type"))
        assertEquals(HARBOR_COMMAND_TOOL_NAME, def.getJSONObject("function").getString("name"))
    }

    @Test
    fun `parse reads instruction command and intent summary`() {
        val args = HarborCommandTool.parse(
            """{"action":"instruction","command":"git push","intent_summary":"git push を送りますか？"}""",
        )
        assertEquals(HarborCommandAction.INSTRUCTION, args.action)
        assertEquals("git push", args.command)
        assertEquals("git push を送りますか？", args.intentSummary)
        assertFalse(args.needsClarification)
    }

    @Test
    fun `parse key action for enter`() {
        val args = HarborCommandTool.parse(
            """{"action":"key","key":"enter","intent_summary":"Enterキーを送りますか？"}""",
            fallbackCommand = "この内容でエンターを送って",
        )
        assertEquals(HarborCommandAction.KEY, args.action)
        assertEquals("enter", args.key)
        assertTrue(args.command.isEmpty())
    }

    @Test
    fun `STT enter phrase forces key even if LLM returned instruction`() {
        val args = HarborCommandTool.parse(
            """{"action":"instruction","command":"Enter","intent_summary":"Enterを送りますか？"}""",
            fallbackCommand = "この内容でエンターを送って",
        )
        assertEquals(HarborCommandAction.KEY, args.action)
        assertEquals("enter", args.key)
    }

    @Test
    fun `parse clarification prefers question`() {
        val args = HarborCommandTool.parse(
            """{"action":"instruction","command":"","intent_summary":"確認","needs_clarification":true,"question":"何を送りますか？"}""",
            fallbackCommand = "曖昧な指示",
        )
        assertTrue(args.needsClarification)
        assertEquals("何を送りますか？", args.intentSummary)
        assertEquals("曖昧な指示", args.command)
    }

    @Test
    fun `parse falls back on broken json`() {
        val args = HarborCommandTool.parse("{not-json", fallbackCommand = "そのまま")
        assertEquals(HarborCommandAction.INSTRUCTION, args.action)
        assertEquals("そのまま", args.command)
        assertEquals("そのまま送信します", args.intentSummary)
    }

    @Test
    fun `fallback marks blank as clarification and enter STT as key`() {
        val blank = HarborCommandTool.fallback("  ")
        assertTrue(blank.needsClarification)
        assertTrue(blank.question!!.isNotBlank())

        val enter = HarborCommandTool.fallback("エンターを送って")
        assertEquals(HarborCommandAction.KEY, enter.action)
        assertEquals("enter", enter.key)
    }

    @Test
    fun `switch_workspace carries the target and does not become an instruction`() {
        val args = HarborCommandTool.parse(
            """{"action":"switch_workspace","workspace":"terminal-harbor",
               "intent_summary":"terminal-harbor に切り替えますか？"}""",
            fallbackCommand = "ターミナルハーバーに切り替えて",
        )

        assertEquals(HarborCommandAction.SWITCH_WORKSPACE, args.action)
        assertEquals("terminal-harbor", args.workspace)
        assertEquals("", args.command)
        assertFalse(args.needsClarification)
    }

    @Test
    fun `switch_workspace survives an STT that looks like an enter request`() {
        // normalizeWithStt rewrites INSTRUCTION into KEY for enter-ish speech; a
        // confirmed switch must not be rewritten the same way.
        val args = HarborCommandTool.parse(
            """{"action":"switch_workspace","workspace":"harbor","intent_summary":"切り替え"}""",
            fallbackCommand = "送信して",
        )

        assertEquals(HarborCommandAction.SWITCH_WORKSPACE, args.action)
        assertEquals("harbor", args.workspace)
    }

    @Test
    fun `an instruction that merely names a workspace stays an instruction`() {
        val args = HarborCommandTool.parse(
            """{"action":"instruction",
               "command":"voice-harness-even-g2 と terminal-harbor を整理してコミットして",
               "intent_summary":"整理してコミットしますか？"}""",
            fallbackCommand = "整理してコミットして",
        )

        assertEquals(HarborCommandAction.INSTRUCTION, args.action)
        assertNull(args.workspace)
    }

    @Test
    fun `isEnterRequest matches common phrases`() {
        assertTrue(HarborCommandTool.isEnterRequest("この内容でエンターを送って"))
        assertTrue(HarborCommandTool.isEnterRequest("Enterを押して"))
        assertTrue(HarborCommandTool.isEnterRequest("送信して"))
        assertFalse(HarborCommandTool.isEnterRequest("git pushして"))
    }

    @Test
    fun `parse strips a trailing quotative send cue from command`() {
        val args = HarborCommandTool.parse(
            """{"action":"instruction","command":"問題なさそうと送って","intent_summary":""}""",
        )
        assertEquals(HarborCommandAction.INSTRUCTION, args.action)
        assertEquals("問題なさそう", args.command)

        val viaSttOnly = HarborCommandTool.parse(
            """{"action":"instruction","command":"","intent_summary":""}""",
            fallbackCommand = "問題なさそうと送信して",
        )
        assertEquals("問題なさそう", viaSttOnly.command)
    }

    @Test
    fun `fallback strips a trailing quotative send cue`() {
        val args = HarborCommandTool.fallback("問題なさそうと送って")
        assertEquals(HarborCommandAction.INSTRUCTION, args.action)
        assertEquals("問題なさそう", args.command)
        assertFalse(args.needsClarification)
    }

    @Test
    fun `bare send verb without the quotative と is left untouched`() {
        val args = HarborCommandTool.fallback("ファイルを送って")
        assertEquals("ファイルを送って", args.command)
    }
}
