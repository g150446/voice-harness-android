package com.g150446.voiceharness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenClawHarborSessionTest {
    @Test
    fun `one session per workspace`() {
        assertEquals(
            "voice-harness:abc:harbor:ws-1",
            harborSessionKey("voice-harness:abc", "ws-1"),
        )
        assertTrue(
            harborSessionKey("voice-harness:abc", "ws-1") !=
                harborSessionKey("voice-harness:abc", "ws-2"),
        )
    }

    @Test
    fun `an unknown workspace keeps the shared harbor session`() {
        assertEquals("voice-harness:abc:harbor", harborSessionKey("voice-harness:abc", null))
        assertEquals("voice-harness:abc:harbor", harborSessionKey("voice-harness:abc", "   "))
    }

    @Test
    fun `a workspace id cannot introduce a namespace of its own`() {
        assertEquals(
            "voice-harness:abc:harbor:subagent-evil",
            harborSessionKey("voice-harness:abc", "subagent:evil"),
        )
        assertEquals(
            "voice-harness:abc:harbor:a-b",
            harborSessionKey("voice-harness:abc", "  a/b  "),
        )
    }

    @Test
    fun `interpretation sessions are hidden from the chat session picker`() {
        assertTrue(isHarborSessionKey("voice-harness:abc:harbor"))
        assertTrue(isHarborSessionKey("voice-harness:abc:harbor:ws-1"))
        assertFalse(isHarborSessionKey("voice-harness:abc"))
        assertFalse(isHarborSessionKey("main"))
    }

    @Test
    fun `a named agent target scopes the session key`() {
        assertEquals(
            "agent:harbor-voice:voice-harness:abc:harbor:ws-1",
            agentScopedSessionKey("openclaw/harbor-voice", "voice-harness:abc:harbor:ws-1"),
        )
        assertEquals("agent:main:voice-harness:abc", agentScopedSessionKey("openclaw/main", "voice-harness:abc"))
    }

    @Test
    fun `a single-agent Gateway keeps its unscoped keys`() {
        assertEquals("voice-harness:abc", agentScopedSessionKey("openclaw/default", "voice-harness:abc"))
        assertEquals("voice-harness:abc", agentScopedSessionKey("openclaw", "voice-harness:abc"))
        assertEquals("voice-harness:abc", agentScopedSessionKey("", "voice-harness:abc"))
        assertNull(openClawAgentId("openclaw/default"))
        assertEquals("main", openClawAgentId("openclaw/main"))
    }

    @Test
    fun `an already scoped key is left as the user chose it`() {
        assertEquals(
            "agent:main:existing",
            agentScopedSessionKey("openclaw/harbor-voice", "agent:main:existing"),
        )
    }

    @Test
    fun `the session picker drops every interpretation session`() {
        val body = """
            {"ok":true,"result":{"details":{"sessions":[
              {"key":"main","updatedAt":3},
              {"key":"voice-harness:abc","updatedAt":2},
              {"key":"voice-harness:abc:harbor","updatedAt":1},
              {"key":"voice-harness:abc:harbor:ws-1","updatedAt":1}
            ]}}}
        """.trimIndent()

        val sessions = OpenClawHistoryParser.parseSessions(body)

        assertEquals(listOf("main", "voice-harness:abc"), sessions.map { it.key })
    }
}
