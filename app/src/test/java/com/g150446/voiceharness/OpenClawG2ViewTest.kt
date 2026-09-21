package com.g150446.voiceharness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenClawG2ViewTest {
    private fun user(text: String) = OpenClawHistoryMessage("user", text)
    private fun bot(text: String) = OpenClawHistoryMessage("assistant", text)

    @Test
    fun `empty history renders nothing`() {
        assertNull(openClawConversationG2Text(emptyList()))
        assertNull(openClawConversationG2Text(listOf(user("  "))))
    }

    @Test
    fun `only the last question and its reply are shown`() {
        val text = openClawConversationG2Text(
            listOf(user("one"), bot("A1"), user("two"), bot("A2")),
        )
        assertEquals("あなた: two\n\nOpenClaw: A2", text)
    }

    @Test
    fun `older turns are never shown`() {
        val history = (1..10).flatMap { listOf(user("q$it"), bot("a$it")) }
        val text = openClawConversationG2Text(history)!!
        assertEquals("あなた: q10\n\nOpenClaw: a10", text)
        assertFalse(text.contains("q9"))
    }

    @Test
    fun `an unanswered last question is shown alone`() {
        assertEquals(
            "あなた: two",
            openClawConversationG2Text(listOf(user("one"), bot("A1"), user("two"))),
        )
    }

    @Test
    fun `assistant message before any question is shown as the reply-only turn`() {
        assertEquals("OpenClaw: hello", openClawConversationG2Text(listOf(bot("hello"))))
    }

    @Test
    fun `only the final assistant message is the reply`() {
        val text = openClawConversationG2Text(
            listOf(user("q"), bot("let me check"), bot("final answer")),
        )
        assertEquals("あなた: q\n\nOpenClaw: final answer", text)
    }

    @Test
    fun `a long question is cut short so the reply keeps the room`() {
        val text = openClawConversationG2Text(
            listOf(user("あ".repeat(500)), bot("reply")),
        )!!
        val question = text.substringBefore("\n\nOpenClaw:")
        assertTrue(question.endsWith("…"))
        assertTrue(question.length <= "あなた: ".length + OPENCLAW_G2_MAX_USER_CHARS + 1)
    }

    @Test
    fun `a long reply keeps its length for glass paging and is only capped as a safety`() {
        val text = openClawConversationG2Text(listOf(user("q"), bot("x".repeat(2_000))))!!
        assertFalse(text.endsWith("…"))
        assertTrue(text.length > 2_000)
        val huge = openClawConversationG2Text(listOf(user("q"), bot("x".repeat(10_000))))!!
        assertTrue(huge.endsWith("…"))
        assertTrue(huge.length < OPENCLAW_G2_MAX_REPLY_CHARS + 40)
    }

    @Test
    fun `truncation never splits a surrogate pair`() {
        val emoji = "😀".repeat(2_000)
        val text = openClawConversationG2Text(listOf(user("q"), bot(emoji)))!!
        assertFalse(Character.isHighSurrogate(text[text.length - 2]))
    }

    @Test
    fun `pending text shows the sent message and thinking`() {
        assertEquals("あなた: hi\n\nOpenClaw: 考え中…", openClawPendingG2Text(" hi "))
    }

    @Test
    fun `merged live reply renders the same as the Gateway copy so the page does not reset`() {
        val live = openClawConversationG2Text(mergeLatestExchange(emptyList(), "q", "answer"))
        val stored = openClawConversationG2Text(listOf(user("q"), bot("interim"), bot("answer")))
        assertEquals(live, stored)
    }

    @Test
    fun `merge leaves history alone when it already ends with the reply`() {
        val history = listOf(user("q"), bot("answer"))
        assertEquals(history, mergeLatestExchange(history, "q", " answer "))
    }

    @Test
    fun `merge appends the question and reply the Gateway has not stored yet`() {
        val history = listOf(user("old"), bot("older"))
        val merged = mergeLatestExchange(history, "new", "fresh")
        assertEquals(history + user("new") + bot("fresh"), merged)
    }

    @Test
    fun `merge does not duplicate a question that is already the last one`() {
        val history = listOf(user("q"))
        assertEquals(listOf(user("q"), bot("a")), mergeLatestExchange(history, "q", "a"))
    }

    @Test
    fun `merge strips control text from the reply and ignores empty replies`() {
        val history = listOf(user("q"))
        assertEquals(history, mergeLatestExchange(history, "q", "<think>x</think>"))
        assertEquals(history, mergeLatestExchange(history, "q", null))
    }
}
