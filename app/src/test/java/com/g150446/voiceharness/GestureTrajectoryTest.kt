package com.g150446.voiceharness

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureTrajectoryTest {

    @After
    fun tearDown() = GestureTrajectoryStore.clear()

    private fun f(v: Float) =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array()

    private fun sample(tMs: Int, flags: Int, base: Float): ByteArray =
        byteArrayOf((tMs and 0xFF).toByte(), (tMs shr 8).toByte(), flags.toByte()) +
            f(base) + f(base + 1) + f(base + 2) +
            f(base + 3) + f(base + 4) + f(base + 5)

    private fun begin(result: Int, count: Int) {
        GestureTrajectoryStore.onBegin(
            session = 7,
            result = result,
            reason = 0x00,
            sampleCount = count,
            periodMs = 25,
            gyroBiasY = -9.5f,
        )
    }

    @Test
    fun `capture ack decodes`() {
        assertEquals(
            BleEvent.GestureCaptureAck(enabled = true),
            parseGestureCaptureAck(byteArrayOf(0x00, 0x55, 0x39, 0x01)),
        )
        assertEquals(
            BleEvent.GestureCaptureAck(enabled = false),
            parseGestureCaptureAck(byteArrayOf(0x00, 0x55, 0x39, 0x00)),
        )
    }

    @Test
    fun `capture ack rejects truncated and foreign packets`() {
        assertNull(parseGestureCaptureAck(byteArrayOf(0x00, 0x55, 0x39)))
        assertNull(parseGestureCaptureAck(byteArrayOf(0x00, 0x54, 0x39, 0x01)))
        assertNull(parseGestureCaptureAck(byteArrayOf(0x00, 0x55, 0x40, 0x01)))
        // A payload-carrying event must not fall through to the payload-free parser.
        assertNull(parseSimpleBleEvent(byteArrayOf(0x00, 0x55, 0x39)))
    }

    @Test
    fun `sample layout matches the firmware packing`() {
        assertEquals(27, TRAJECTORY_SAMPLE_BYTES)
        assertEquals(27, sample(0, 0, 0f).size)
    }

    @Test
    fun `a full batch assembles in order`() {
        begin(result = 1, count = 3)
        GestureTrajectoryStore.onChunk(
            0,
            listOf(
                GestureTrajectorySample(0, 0, 1f, 2f, 3f, 0f, 0f, 0f),
                GestureTrajectorySample(25, 1, 4f, 5f, 6f, 7f, 8f, 9f),
            ),
        )
        GestureTrajectoryStore.onChunk(
            2,
            listOf(GestureTrajectorySample(50, 1, 10f, 11f, 12f, 13f, 14f, 15f)),
        )
        val t = GestureTrajectoryStore.onEnd(sentCount = 3, flags = 0)
        assertNotNull(t)
        requireNotNull(t)
        assertEquals(3, t.samples.size)
        assertEquals(50, t.samples.last().tMs)
        assertTrue(t.complete)
        assertTrue(t.isMatch)
        // Gyro is off until the palm-up latch; the flag says which samples are real.
        assertFalse(t.samples.first().gyroEnabled)
        assertTrue(t.samples[1].gyroEnabled)
    }

    @Test
    fun `overflow and notify errors mark the batch incomplete`() {
        begin(result = 2, count = 2)
        GestureTrajectoryStore.onChunk(
            0,
            listOf(GestureTrajectorySample(0, 0, 1f, 1f, 1f, 0f, 0f, 0f)),
        )
        val t = requireNotNull(GestureTrajectoryStore.onEnd(sentCount = 1, flags = 0x03))
        assertTrue(t.overflow)
        assertTrue(t.notifyError)
        assertFalse(t.complete)
        assertFalse(t.isMatch)
    }

    @Test
    fun `chunks without a begin are dropped`() {
        GestureTrajectoryStore.onChunk(
            0,
            listOf(GestureTrajectorySample(0, 0, 1f, 1f, 1f, 0f, 0f, 0f)),
        )
        assertNull(GestureTrajectoryStore.onEnd(sentCount = 1, flags = 0))
    }

    @Test
    fun `only a matching attempt is attached to a recording`() {
        begin(result = 2, count = 1)
        GestureTrajectoryStore.onChunk(
            0,
            listOf(GestureTrajectorySample(0, 0, 1f, 1f, 1f, 0f, 0f, 0f)),
        )
        GestureTrajectoryStore.onEnd(sentCount = 1, flags = 0)
        // A failed sequence never started a recording, so it must not be claimed.
        assertNull(GestureTrajectoryStore.takeForRecording(System.currentTimeMillis()))
    }

    @Test
    fun `a match is claimed once and only inside the window`() {
        begin(result = 1, count = 1)
        GestureTrajectoryStore.onChunk(
            0,
            listOf(GestureTrajectorySample(0, 0, 1f, 1f, 1f, 0f, 0f, 0f)),
        )
        GestureTrajectoryStore.onEnd(sentCount = 1, flags = 0)
        val now = System.currentTimeMillis()
        assertNull(GestureTrajectoryStore.takeForRecording(now - 60_000L))
        assertNotNull(GestureTrajectoryStore.takeForRecording(now))
        // Claiming it twice would attach one attempt to two history entries.
        assertNull(GestureTrajectoryStore.takeForRecording(now))
    }

    @Test
    fun `csv carries the header and every sample`() {
        begin(result = 1, count = 2)
        GestureTrajectoryStore.onChunk(
            0,
            listOf(
                GestureTrajectorySample(0, 0, 1f, 2f, 3f, 0f, 0f, 0f),
                GestureTrajectorySample(25, 7, 4f, 5f, 6f, 7f, 8f, 9f),
            ),
        )
        val csv = requireNotNull(GestureTrajectoryStore.onEnd(2, 0)).toCsv()
        val lines = csv.trim().lines()
        assertTrue(lines[0].startsWith("# session=7 result=1"))
        assertEquals("t_ms,flags,ax,ay,az,gx,gy,gz", lines[1])
        assertEquals(4, lines.size)
        assertTrue(lines[3].startsWith("25,7,"))
    }
}
