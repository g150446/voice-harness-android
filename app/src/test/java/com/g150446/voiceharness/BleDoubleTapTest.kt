package com.g150446.voiceharness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class BleDoubleTapTest {

    @Test
    fun `0x12 event packet decodes as double tap`() {
        val event = parseSimpleBleEvent(byteArrayOf(0x00, 0x55, 0x12))
        assertSame(BleEvent.DoubleTap, event)
    }

    @Test
    fun `0x14 event packet decodes as single tap`() {
        val event = parseSimpleBleEvent(byteArrayOf(0x00, 0x55, 0x14))
        assertSame(BleEvent.SingleTap, event)
    }

    @Test
    fun `short malformed and unknown packets do not decode`() {
        assertNull(parseSimpleBleEvent(byteArrayOf(0x00, 0x55)))
        assertNull(parseSimpleBleEvent(byteArrayOf(0x00, 0x54, 0x12)))
        assertNull(parseSimpleBleEvent(byteArrayOf(0x00, 0x55, 0x7F)))
    }

    @Test
    fun `existing recording stop event remains unchanged`() {
        assertSame(
            BleEvent.RecordingStopped,
            parseSimpleBleEvent(byteArrayOf(0x00, 0x55, 0x02)),
        )
    }

    @Test
    fun `double tap status increments and records detection time`() {
        val updated = nextDoubleTapStatus(
            current = DoubleTapStatus(count = 2, lastDetectedAtMillis = 100),
            detectedAtMillis = 456,
        )
        assertEquals(3L, updated.count)
        assertEquals(456L, updated.lastDetectedAtMillis)
    }

    @Test
    fun `single tap status increments and records detection time`() {
        val updated = nextSingleTapStatus(
            current = SingleTapStatus(count = 1, lastDetectedAtMillis = 200),
            detectedAtMillis = 789,
        )
        assertEquals(2L, updated.count)
        assertEquals(789L, updated.lastDetectedAtMillis)
    }
}
