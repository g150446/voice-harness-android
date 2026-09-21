package com.g150446.voiceharness

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EvenG2ConversationSessionTest {
    @After
    fun reset() = EvenG2ReadingSession.setEnabled(false)

    @Test
    fun `conversation is exposed to the plugin as an ordinary response`() {
        EvenG2ReadingSession.publishConversation("あなた: hi")
        val snapshot = EvenG2ReadingSession.snapshot()
        assertEquals(EvenG2DisplayMode.RESPONSE, snapshot.mode)
        assertEquals("あなた: hi", snapshot.bodyText)
    }

    @Test
    fun `an identical conversation keeps the revision so the glass page does not reset`() {
        EvenG2ReadingSession.publishConversation("same")
        val revision = EvenG2ReadingSession.snapshot().revision
        EvenG2ReadingSession.publishConversation("same")
        assertEquals(revision, EvenG2ReadingSession.snapshot().revision)
        EvenG2ReadingSession.publishConversation("changed")
        assertNotEquals(revision, EvenG2ReadingSession.snapshot().revision)
    }

    @Test
    fun `another publisher makes the next identical conversation repaint`() {
        EvenG2ReadingSession.publishConversation("same")
        EvenG2ReadingSession.publishResponse("OpenClawモード")
        val revision = EvenG2ReadingSession.snapshot().revision
        EvenG2ReadingSession.publishConversation("same")
        assertNotEquals(revision, EvenG2ReadingSession.snapshot().revision)
        assertEquals("same", EvenG2ReadingSession.snapshot().bodyText)
    }

    @Test
    fun `clearing the display makes the next identical conversation repaint`() {
        EvenG2ReadingSession.publishConversation("same")
        EvenG2ReadingSession.clearDisplay()
        EvenG2ReadingSession.publishConversation("same")
        assertEquals("same", EvenG2ReadingSession.snapshot().bodyText)
        assertEquals(true, EvenG2ReadingSession.snapshot().active)
    }
}
