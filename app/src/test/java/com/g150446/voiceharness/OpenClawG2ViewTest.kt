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
    fun `newest exchange comes first and each exchange keeps question then reply`() {
        val text = openClawConversationG2Text(
            listOf(user("one"), bot("A1"), user("two"), bot("A2")),
        )
        assertEquals("あなた: two\nOpenClaw: A2\n\nあなた: one\nOpenClaw: A1", text)
    }

    @Test
    fun `assistant message before any question is its own turn`() {
        val text = openClawConversationG2Text(listOf(bot("hello"), user("hi"), bot("yo")))
        assertEquals("あなた: hi\nOpenClaw: yo\n\nOpenClaw: hello", text)
    }

    @Test
    fun `only the newest turns are kept`() {
        val history = (1..10).flatMap { listOf(user("q$it"), bot("a$it")) }
        val text = openClawConversationG2Text(history)!!
        assertTrue(text.startsWith("あなた: q10"))
        assertTrue(text.contains("q7"))
        assertFalse(text.contains("q6"))
    }

    @Test
    fun `older turns are dropped once the budget is used but the newest is always shown`() {
        val long = "あ".repeat(OPENCLAW_G2_MAX_MESSAGE_CHARS)
        val text = openClawConversationG2Text(
            listOf(user("old"), bot(long), user("new"), bot(long), bot(long)),
        )!!
        assertTrue(text.startsWith("あなた: new"))
        assertFalse(text.contains("あなた: old"))
        assertTrue(text.length <= OPENCLAW_G2_MAX_CHARS + 40)
    }

    @Test
    fun `a single long message is truncated with an ellipsis`() {
        val text = openClawConversationG2Text(listOf(user("q"), bot("x".repeat(2_000))))!!
        assertTrue(text.endsWith("…"))
        assertTrue(text.length < 600)
    }

    @Test
    fun `truncation never splits a surrogate pair`() {
        val emoji = "😀".repeat(400)
        val text = openClawConversationG2Text(listOf(user("q"), bot(emoji)))!!
        assertFalse(Character.isHighSurrogate(text[text.length - 2]))
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
