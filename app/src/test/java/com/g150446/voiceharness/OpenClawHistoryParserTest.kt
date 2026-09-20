package com.g150446.voiceharness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenClawHistoryParserTest {
    @Test
    fun `history keeps only visible user and assistant text`() {
        val body = """
            {"ok":true,"result":{"content":[{"type":"text","text":"{}"}],"details":{"sessionKey":"main","messages":[
              {"role":"user","content":"こんにちは"},
              {"role":"assistant","content":[{"type":"thinking","thinking":"x"},{"type":"text","text":"[[reply_to_current]] はい"},{"type":"toolCall","name":"exec"}]},
              {"role":"toolResult","content":"secret output"},
              {"role":"assistant","content":"NO_REPLY"},
              {"role":"assistant","content":"<think>hidden</think>答え<tool_call>{\"a\":1}</tool_call>"},
              {"role":"assistant","content":[{"type":"toolCall","name":"exec"}]},
              {"role":"system","content":"sys"}
            ]}}}
        """.trimIndent()

        assertEquals(
            listOf(
                OpenClawHistoryMessage("user", "こんにちは"),
                OpenClawHistoryMessage("assistant", "はい"),
                OpenClawHistoryMessage("assistant", "答え"),
            ),
            OpenClawHistoryParser.parseHistory(body),
        )
    }

    @Test
    fun `history falls back to JSON text content when details are absent`() {
        val inner = """{"messages":[{"role":"user","content":"hi"}]}""".replace("\"", "\\\"")
        val body = """{"ok":true,"result":{"content":[{"type":"text","text":"$inner"}]}}"""

        assertEquals(listOf(OpenClawHistoryMessage("user", "hi")), OpenClawHistoryParser.parseHistory(body))
    }

    @Test
    fun `truncated tool call xml and control tokens are stripped`() {
        assertEquals("ok", OpenClawHistoryParser.sanitize("ok<|im_end|><function_calls>{broken"))
    }

    @Test
    fun `sessions are sorted newest first and hide reserved and harbor sessions`() {
        val body = """
            {"ok":true,"result":{"details":{"count":5,"sessions":[
              {"key":"voice-harness:a","updatedAt":10,"lastMessagePreview":"old"},
              {"key":"main","displayName":"Main","updatedAt":30,"lastMessagePreview":"latest"},
              {"key":"agent:main:cron:job","updatedAt":40},
              {"key":"agent:main:subagent:x","updatedAt":50},
              {"key":"voice-harness:a:harbor","updatedAt":60}
            ]}}}
        """.trimIndent()

        val sessions = OpenClawHistoryParser.parseSessions(body)

        assertEquals(listOf("main", "voice-harness:a"), sessions.map { it.key })
        assertEquals("Main", sessions[0].title)
        assertEquals("voice-harness:a", sessions[1].title)
        assertTrue(sessions[0].preview == "latest")
    }
}
