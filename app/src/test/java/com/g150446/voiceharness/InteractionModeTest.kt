package com.g150446.voiceharness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class InteractionModeTest {
    @Test
    fun `Japanese and English commands select each mode`() {
        assertEquals(InteractionMode.HARBOR, parseInteractionMode("ハーバーモードにして"))
        assertEquals(InteractionMode.HARBOR, parseInteractionMode("Terminal Harbor"))
        assertEquals(InteractionMode.AI, parseInteractionMode("AI 対話モード"))
        assertEquals(InteractionMode.READER, parseInteractionMode("読書モード"))
        assertEquals(InteractionMode.READER, parseInteractionMode("reader mode"))
    }

    @Test
    fun `ambiguous or unknown command does not switch`() {
        assertNull(parseInteractionMode("ハーバーからAIへ"))
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
}
