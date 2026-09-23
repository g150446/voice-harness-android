package com.g150446.voiceharness

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HarborTranscriptTest {
    private fun page(
        messages: String,
        hasMore: Boolean = false,
        nextBefore: String? = null,
    ) = JSONObject(
        """
        {"workspace_id":"ws-1","agent":"Claude","capability":"agent_transcript",
         "available":true,"source":"claude_transcript",
         "messages":[$messages],
         "has_more":$hasMore
         ${nextBefore?.let { ""","next_before":"$it"""" } ?: ""}}
        """.trimIndent(),
    )

    @Test
    fun `a page keeps order and every field`() {
        val transcript = parseHarborTranscript(
            200,
            page(
                """
                {"role":"user","text":"テストを直して","at":"2026-09-22T10:00:00Z",
                 "cursor":"100","truncated":false},
                {"role":"assistant","text":"直しました","at":"2026-09-22T10:00:31Z",
                 "cursor":"220","truncated":false}
                """.trimIndent(),
                hasMore = true,
                nextBefore = "100",
            ),
        )

        assertTrue(transcript.available)
        assertEquals("Claude", transcript.agent)
        assertEquals(listOf("user", "assistant"), transcript.messages.map { it.role })
        assertEquals("テストを直して", transcript.messages[0].text)
        assertTrue(transcript.messages[0].isUser)
        assertFalse(transcript.messages[1].isUser)
        assertEquals("2026-09-22T10:00:31Z", transcript.messages[1].at)
        assertEquals("220", transcript.messages[1].cursor)
        assertTrue(transcript.hasMore)
        assertEquals("100", transcript.nextBefore)
    }

    @Test
    fun `a cut message keeps the marker the bridge put there`() {
        val transcript = parseHarborTranscript(
            200,
            page("""{"role":"assistant","text":"長い話…（長いため以降を省略しました）","cursor":"7","truncated":true}"""),
        )

        assertTrue(transcript.messages.single().truncated)
        assertTrue(transcript.messages.single().text.endsWith("省略しました）"))
    }

    @Test
    fun `rows without a role or text are dropped rather than shown blank`() {
        val transcript = parseHarborTranscript(
            200,
            page(
                """
                {"role":"","text":"orphan","cursor":"1"},
                {"role":"user","text":"   ","cursor":"2"},
                {"role":"user","text":"本物","cursor":"3"}
                """.trimIndent(),
            ),
        )

        assertEquals(listOf("本物"), transcript.messages.map { it.text })
    }

    @Test
    fun `no session is a normal answer with a reason and no messages`() {
        val json = JSONObject(
            """{"workspace_id":"ws-1","agent":"Claude","capability":"agent_transcript",
                "available":false,"reason":"session_unidentified"}""",
        )

        val transcript = parseHarborTranscript(200, json)

        assertFalse(transcript.available)
        assertEquals("session_unidentified", transcript.reason)
        assertTrue(transcript.messages.isEmpty())
        assertFalse(transcript.hasMore)
        assertNull(transcript.nextBefore)
        // It must tell the user how to fix it, and never imply the terminal view has it.
        assertTrue(harborTranscriptReasonText(transcript).contains("agent-session install-hooks"))
    }

    @Test
    fun `two sessions that could be the pane are refused rather than guessed`() {
        val transcript = HarborTranscript(
            available = false,
            capability = "agent_transcript",
            agent = "Codex",
            reason = "ambiguous_session",
        )

        assertTrue(harborTranscriptReasonText(transcript).contains("複数"))
    }

    @Test
    fun `an older bridge reads as unsupported, not as an empty conversation`() {
        val transcript = HarborTranscript(
            available = false,
            capability = "unknown",
            reason = "unsupported_api",
        )

        assertTrue(harborTranscriptReasonText(transcript).contains("API 1.12.0"))
    }

    @Test
    fun `every reason produces a message of its own`() {
        val reasons = listOf(
            "no_agent",
            "unsupported_agent",
            "session_unidentified",
            "stale_session",
            "transcript_missing",
            "ambiguous_session",
            "stale_cursor",
            "unsupported_api",
        )

        val texts = reasons.map {
            harborTranscriptReasonText(
                HarborTranscript(available = false, capability = "agent_transcript", reason = it),
            )
        }

        assertEquals(reasons.size, texts.toSet().size)
        assertTrue(texts.none { it.contains("端末") })
    }

    private fun message(cursor: String, text: String = "m$cursor") =
        HarborTranscriptMessage(role = "user", text = text, cursor = cursor)

    @Test
    fun `a refresh appends what is new and keeps the history already pulled in`() {
        // The reader walked back to "1"; the agent has since answered with "7".
        val current = listOf(message("1"), message("2"), message("5"))

        val merged = mergeHarborTranscript(
            current,
            listOf(message("2"), message("5"), message("7")),
            before = null,
        )

        assertEquals(listOf("1", "2", "5", "7"), merged.map { it.cursor })
    }

    @Test
    fun `a refresh with nothing in common replaces rather than faking continuity`() {
        val current = listOf(message("1"), message("2"))

        val merged = mergeHarborTranscript(current, listOf(message("80"), message("90")), before = null)

        assertEquals(listOf("80", "90"), merged.map { it.cursor })
    }

    @Test
    fun `the first page is taken as it comes`() {
        val merged = mergeHarborTranscript(emptyList(), listOf(message("9")), before = null)

        assertEquals(listOf("9"), merged.map { it.cursor })
    }

    @Test
    fun `an older page goes in front and keeps the order`() {
        val current = listOf(message("5"), message("6"))

        val merged = mergeHarborTranscript(current, listOf(message("3"), message("4")), before = "5")

        assertEquals(listOf("3", "4", "5", "6"), merged.map { it.cursor })
    }

    @Test
    fun `an overlapping older page does not repeat what is already shown`() {
        val current = listOf(message("4"), message("5"))

        val merged = mergeHarborTranscript(current, listOf(message("3"), message("4")), before = "4")

        assertEquals(listOf("3", "4", "5"), merged.map { it.cursor })
    }

    @Test
    fun `an unknown reason still produces a message`() {
        val transcript = HarborTranscript(
            available = false,
            capability = "unknown",
            reason = "something_new",
        )

        assertEquals("会話を取得できませんでした。", harborTranscriptReasonText(transcript))
    }
}
