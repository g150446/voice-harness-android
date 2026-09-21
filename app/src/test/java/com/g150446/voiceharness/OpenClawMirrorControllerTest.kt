package com.g150446.voiceharness

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OpenClawMirrorControllerTest {
    private class Fixture(scope: TestScope) {
        var history: List<OpenClawHistoryMessage> = listOf(
            OpenClawHistoryMessage("user", "q"),
            OpenClawHistoryMessage("assistant", "a"),
        )
        var loads = 0
        var state = VoiceState.READY
        var g2 = true
        val conversations = mutableListOf<String>()
        val statuses = mutableListOf<String>()
        val controller = OpenClawMirrorController(
            scope = scope,
            loadHistory = { loads += 1; Result.success(history) },
            voiceState = { state },
            g2Active = { g2 },
            publishConversation = { conversations += it },
            publishStatus = { statuses += it },
            dispatcher = StandardTestDispatcher(scope.testScheduler),
        )
    }

    @Test
    fun `polls the transcript while OpenClaw mode is on and G2 is active`() = runTest {
        val f = Fixture(this)
        f.controller.setMode(InteractionMode.OPENCLAW)
        runCurrent()
        assertEquals(listOf("あなた: q\nOpenClaw: a"), f.conversations)
        advanceTimeBy(OPENCLAW_MIRROR_POLL_MS + 1)
        assertEquals(2, f.loads)
        f.controller.setMode(InteractionMode.AI)
        runCurrent()
    }

    @Test
    fun `does not poll in other modes or without G2`() = runTest {
        val f = Fixture(this)
        f.controller.setMode(InteractionMode.AI)
        f.g2 = false
        f.controller.setMode(InteractionMode.OPENCLAW)
        runCurrent()
        assertEquals(0, f.loads)
    }

    @Test
    fun `leaves the glass alone while recording and announces a pending reply once`() = runTest {
        val f = Fixture(this)
        f.state = VoiceState.RECORDING
        f.controller.setMode(InteractionMode.OPENCLAW)
        runCurrent()
        assertEquals(0, f.loads)
        assertTrue(f.statuses.isEmpty())

        f.state = VoiceState.RESPONDING
        advanceTimeBy(OPENCLAW_MIRROR_POLL_MS + 1)
        advanceTimeBy(OPENCLAW_MIRROR_POLL_MS + 1)
        assertEquals(1, f.statuses.size)
        assertEquals(0, f.loads)

        f.state = VoiceState.READY
        advanceTimeBy(OPENCLAW_MIRROR_POLL_MS + 1)
        assertEquals(1, f.loads)
        assertFalse(f.conversations.isEmpty())
        f.controller.setMode(InteractionMode.AI)
        runCurrent()
    }

    @Test
    fun `showNow merges a reply the Gateway has not stored and reports success`() = runTest {
        val f = Fixture(this)
        val shown = f.controller.showNow("next", "fresh")
        assertTrue(shown)
        assertEquals("あなた: next\nOpenClaw: fresh\n\nあなた: q\nOpenClaw: a", f.conversations.single())
    }

    @Test
    fun `showNow reports failure when there is nothing to show`() = runTest {
        val f = Fixture(this)
        f.history = emptyList()
        assertFalse(f.controller.showNow(null, null))
        assertTrue(f.conversations.isEmpty())
    }
}
