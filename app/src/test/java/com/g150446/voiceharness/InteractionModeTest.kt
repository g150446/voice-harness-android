package com.g150446.voiceharness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractionModeTest {
    @Test
    fun `Japanese and English commands select each mode`() {
        assertEquals(InteractionMode.HARBOR, parseInteractionMode("ハーバーモードにして"))
        assertEquals(InteractionMode.HARBOR, parseInteractionMode("Terminal Harbor"))
        assertEquals(InteractionMode.AI, parseInteractionMode("Pilot モード"))
        assertEquals(InteractionMode.READER, parseInteractionMode("読書モード"))
        assertEquals(InteractionMode.READER, parseInteractionMode("reader mode"))
    }

    @Test
    fun `OpenClaw commands select OpenClaw mode`() {
        assertEquals(InteractionMode.OPENCLAW, parseInteractionMode("OpenClawモード"))
        assertEquals(InteractionMode.OPENCLAW, parseInteractionMode("open claw"))
        assertEquals(InteractionMode.OPENCLAW, parseInteractionMode("オープンクローモードにして"))
        assertEquals(InteractionMode.OPENCLAW, parseInteractionMode("OpenClawチャット"))
    }

    @Test
    fun `OpenClaw mixed with another mode is ambiguous`() {
        assertNull(parseInteractionMode("ハーバーからOpenClawへ"))
        assertNull(parseInteractionMode("OpenClawとリーダー"))
    }

    @Test
    fun `OpenClaw mode requires a configured token but never G2`() {
        assertTrue(
            canEnableInteractionMode(
                InteractionMode.OPENCLAW,
                g2Active = false,
                harborPaired = false,
                openClawConfigured = true,
            ),
        )
        assertFalse(
            canEnableInteractionMode(
                InteractionMode.OPENCLAW,
                g2Active = true,
                harborPaired = true,
                openClawConfigured = false,
            ),
        )
    }

    @Test
    fun `ambiguous or unknown command does not switch`() {
        assertNull(parseInteractionMode("ハーバーからパイロットへ"))
        assertNull(parseInteractionMode("音楽モード"))
    }

    @Test
    fun `Terminal Harbor device key follows the v2 HKDF contract`() {
        val expected = "8ebd44541fcbc44b2b9786e0f60034acb73af9f8421b1ae59c1cf502c94cfb88"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertArrayEquals(
            expected,
            hkdfDeviceKey("pair-token", "server-one", "client-one", ByteArray(32) { it.toByte() }),
        )
    }

    @Test
    fun `Harbor mode requires pairing but never requires G2`() {
        assertTrue(
            canEnableInteractionMode(
                InteractionMode.HARBOR,
                g2Active = false,
                harborPaired = true,
            ),
        )
        assertFalse(
            canEnableInteractionMode(
                InteractionMode.HARBOR,
                g2Active = true,
                harborPaired = false,
            ),
        )
        assertFalse(
            canEnableInteractionMode(
                InteractionMode.READER,
                g2Active = false,
                harborPaired = true,
            ),
        )
    }

    @Test
    fun `EPUB reader is named by voice without colliding with the Kindle reader`() {
        assertEquals(InteractionMode.EPUB, parseInteractionMode("EPUBモード"))
        assertEquals(InteractionMode.EPUB, parseInteractionMode("イーパブリーダー"))
        assertEquals(InteractionMode.EPUB, parseInteractionMode("epubリーダー"))
        assertEquals(InteractionMode.READER, parseInteractionMode("リーダー"))
        assertEquals(InteractionMode.READER, parseInteractionMode("リーダーモード"))
    }

    @Test
    fun `Pilot names the AI mode and the former AI対話 name is retired`() {
        assertEquals(InteractionMode.AI, parseInteractionMode("パイロットモード"))
        assertEquals(InteractionMode.AI, parseInteractionMode("Pilotモードに切り替えて"))
        assertNull(parseInteractionMode("AI対話モード"))
        assertNull(parseInteractionMode("チャットモード"))
    }

    @Test
    fun `EPUB mode needs an opened book and never needs G2`() {
        assertTrue(
            canEnableInteractionMode(
                InteractionMode.EPUB, g2Active = false, harborPaired = false, epubReady = true,
            ),
        )
        assertFalse(
            canEnableInteractionMode(
                InteractionMode.EPUB, g2Active = true, harborPaired = true, epubReady = false,
            ),
        )
    }

    @Test
    fun `Harbor reopens its active workspace, then the remembered one`() {
        val a = HarborWorkspace(id = "a", name = "a", selected = false)
        val b = HarborWorkspace(id = "b", name = "b", selected = true)
        assertEquals("b", lastHarborWorkspaceId(listOf(a, b), "a", "a"))
        assertEquals("a", lastHarborWorkspaceId(listOf(a), "a", null))
        assertEquals("a", lastHarborWorkspaceId(emptyList(), null, "a"))
        // A remembered workspace that has since closed is not reopened.
        assertNull(lastHarborWorkspaceId(listOf(a), "gone", null))
        assertNull(lastHarborWorkspaceId(emptyList(), null, null))
    }

    @Test
    fun `a bare mode request switches without the glasses prefix`() {
        assertEquals(InteractionMode.AI, spokenInteractionModeSwitch("パイロットモードに切り替えて"))
        assertEquals(InteractionMode.AI, spokenInteractionModeSwitch("パイロットモードに切り替え。"))
        assertEquals(InteractionMode.HARBOR, spokenInteractionModeSwitch("ハーバーモードにして"))
        assertEquals(InteractionMode.HARBOR, spokenInteractionModeSwitch("ターミナルハーバーモードに切り替えて"))
        assertEquals(InteractionMode.READER, spokenInteractionModeSwitch("リーダーモード"))
        assertEquals(InteractionMode.OPENCLAW, spokenInteractionModeSwitch("OpenClawモードへ変更"))
        // Claude Code's plan mode and sentences that only mention a mode go to the LLM.
        assertNull(spokenInteractionModeSwitch("プランモードに切り替えて"))
        assertNull(spokenInteractionModeSwitch("Pilotモードの説明をして"))
        assertNull(spokenInteractionModeSwitch("AI対話モードに切り替えて"))
    }
}
