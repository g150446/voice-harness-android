package com.g150446.voiceharness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BleOperationModeAckTest {

    @Test
    fun `0x40 ack decodes effective mode with nothing pending`() {
        val ack = parseOperationModeAck(
            byteArrayOf(0x00, 0x55, 0x40, 0x01, 0xFF.toByte())
        )
        assertEquals(0x01, ack?.effective)
        assertEquals(OPERATION_MODE_PENDING_NONE, ack?.pending)
    }

    @Test
    fun `0x40 ack decodes a switch deferred until recording ends`() {
        val ack = parseOperationModeAck(byteArrayOf(0x00, 0x55, 0x40, 0x00, 0x01))
        assertEquals(0x00, ack?.effective)
        assertEquals(0x01, ack?.pending)
    }

    @Test
    fun `truncated and malformed 0x40 packets do not decode`() {
        assertNull(parseOperationModeAck(byteArrayOf(0x00, 0x55, 0x40, 0x01)))
        assertNull(parseOperationModeAck(byteArrayOf(0x00, 0x54, 0x40, 0x01, 0x00)))
        assertNull(parseOperationModeAck(byteArrayOf(0x00, 0x55, 0x12, 0x01, 0x00)))
    }

    @Test
    fun `payload carrying 0x40 stays out of the payload-free parser`() {
        assertNull(parseSimpleBleEvent(byteArrayOf(0x00, 0x55, 0x40)))
        assertNull(parseSimpleBleEvent(byteArrayOf(0x00, 0x55, 0x40, 0x01, 0xFF.toByte())))
    }
}
