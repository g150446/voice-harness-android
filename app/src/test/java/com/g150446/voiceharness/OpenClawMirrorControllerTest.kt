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
        var glassOwned = false
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
            glassOwned = { glassOwned },
        )
    }

    @Test
    fun `polls the transcript while OpenClaw mode is on and G2 is active`() = runTest {
        val f = Fixture(this)
        f.controller.setMode(InteractionMode.OPENCLAW)
        runCurrent()
        assertEquals(listOf("あなた: q\n\nOpenClaw: a"), f.conversations)
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
        assertEquals("あなた: next\n\nOpenClaw: fresh", f.conversations.single())
    }

    @Test
    fun `showNow reports failure when there is nothing to show`() = runTest {
        val f = Fixture(this)
        f.history = emptyList()
        assertFalse(f.controller.showNow(null, null))
        assertTrue(f.conversations.isEmpty())
    }

    @Test
    fun `showPending puts the sent message and thinking on the glass once`() = runTest {
        val f = Fixture(this)
        f.controller.setMode(InteractionMode.OPENCLAW)
        runCurrent()
        f.conversations.clear()
        f.state = VoiceState.RESPONDING
        f.controller.showPending("next")
        advanceTimeBy(OPENCLAW_MIRROR_POLL_MS + 1)
        advanceTimeBy(OPENCLAW_MIRROR_POLL_MS + 1)
        assertEquals(listOf("あなた: next\n\nOpenClaw: 考え中…"), f.statuses)
        assertTrue(f.conversations.isEmpty())
        f.controller.setMode(InteractionMode.AI)
        runCurrent()
    }

    @Test
    fun `leaves the glass alone while another owner such as a send confirm holds it`() = runTest {
        val f = Fixture(this)
        f.glassOwned = true
        f.controller.setMode(InteractionMode.OPENCLAW)
        runCurrent()
        advanceTimeBy(OPENCLAW_MIRROR_POLL_MS * 2 + 1)
        assertTrue(f.conversations.isEmpty())

        f.glassOwned = false
        advanceTimeBy(OPENCLAW_MIRROR_POLL_MS + 1)
        assertFalse(f.conversations.isEmpty())
        f.controller.setMode(InteractionMode.AI)
        runCurrent()
    }
}
