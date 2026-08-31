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
    fun `median period is measured, not the nominal field`() {
        begin(result = 1, count = 4)
        // Node polls at 50 ms in light sleep and 25 ms once active, so a real
        // window mixes both and the nominal 25 must not be trusted.
        GestureTrajectoryStore.onChunk(
            0,
            listOf(
                GestureTrajectorySample(0, 0, 1f, 1f, 1f, 0f, 0f, 0f),
                GestureTrajectorySample(50, 0, 1f, 1f, 1f, 0f, 0f, 0f),
                GestureTrajectorySample(100, 1, 1f, 1f, 1f, 0f, 0f, 0f),
                GestureTrajectorySample(150, 1, 1f, 1f, 1f, 0f, 0f, 0f),
            ),
        )
        val t = requireNotNull(GestureTrajectoryStore.onEnd(4, 0))
        assertEquals(25, t.periodMs)
        assertEquals(50, t.medianPeriodMs())
    }

    @Test
    fun `median period falls back to nominal for a single sample`() {
        begin(result = 1, count = 1)
        GestureTrajectoryStore.onChunk(
            0,
            listOf(GestureTrajectorySample(0, 0, 1f, 1f, 1f, 0f, 0f, 0f)),
        )
        assertEquals(25, requireNotNull(GestureTrajectoryStore.onEnd(1, 0)).medianPeriodMs())
    }

    @Test
    fun `interleaved duplicate batches do not merge`() {
        // Observed on 0.0.91: two flushes of the same 377-sample window arrived
        // with their chunks interleaved, and appending produced 746 samples.
        begin(result = 1, count = 3)
        val chunk = listOf(
            GestureTrajectorySample(0, 0, 1f, 2f, 3f, 0f, 0f, 0f),
            GestureTrajectorySample(25, 0, 4f, 5f, 6f, 0f, 0f, 0f),
        )
        GestureTrajectoryStore.onChunk(0, chunk)
        GestureTrajectoryStore.onChunk(0, chunk)
        GestureTrajectoryStore.onChunk(
            2,
            listOf(GestureTrajectorySample(50, 1, 7f, 8f, 9f, 1f, 2f, 3f)),
        )
        val t = requireNotNull(GestureTrajectoryStore.onEnd(3, 0))
        assertEquals(3, t.samples.size)
        assertEquals(listOf(0, 25, 50), t.samples.map { it.tMs })
        assertTrue(t.complete)
    }

    @Test
    fun `samples beyond the declared count are dropped`() {
        begin(result = 1, count = 2)
        GestureTrajectoryStore.onChunk(
            0,
            listOf(
                GestureTrajectorySample(0, 0, 1f, 1f, 1f, 0f, 0f, 0f),
                GestureTrajectorySample(25, 0, 2f, 2f, 2f, 0f, 0f, 0f),
                GestureTrajectorySample(50, 0, 3f, 3f, 3f, 0f, 0f, 0f),
            ),
        )
        val t = requireNotNull(GestureTrajectoryStore.onEnd(2, 0))
        assertEquals(2, t.samples.size)
    }

    @Test
    fun `a lost chunk is reported as missing rather than silently shifting`() {
        begin(result = 1, count = 4)
        GestureTrajectoryStore.onChunk(
            0,
            listOf(GestureTrajectorySample(0, 0, 1f, 1f, 1f, 0f, 0f, 0f)),
        )
        // index 1 and 2 never arrive
        GestureTrajectoryStore.onChunk(
            3,
            listOf(GestureTrajectorySample(75, 0, 4f, 4f, 4f, 0f, 0f, 0f)),
        )
        val t = requireNotNull(GestureTrajectoryStore.onEnd(2, 0))
        assertEquals(2, t.samples.size)
        assertEquals(2, t.missingSamples)
        assertFalse(t.complete)
        // The surviving samples keep their real timestamps, not compacted ones.
        assertEquals(listOf(0, 75), t.samples.map { it.tMs })
    }

    @Test
    fun `a zero stop time claims nothing`() {
        begin(result = 1, count = 1)
        GestureTrajectoryStore.onChunk(
            0,
            listOf(GestureTrajectorySample(0, 0, 1f, 1f, 1f, 0f, 0f, 0f)),
        )
        GestureTrajectoryStore.onEnd(1, 0)
        // saveHistoryEntry() runs for non-gesture paths too, where no recording
        // stopped; those must not adopt whatever trajectory happens to be held.
        assertNull(GestureTrajectoryStore.takeForRecording(0L))
    }

    @Test
    fun `csv embeds node milestones so the window can be segmented`() {
        begin(result = 1, count = 1)
        GestureTrajectoryStore.onChunk(
            0,
            listOf(GestureTrajectorySample(0, 0, 1f, 2f, 3f, 0f, 0f, 0f)),
        )
        val t = requireNotNull(GestureTrajectoryStore.onEnd(1, 0))
        val csv = t.toCsv(
            listOf(
                GestureDiagEntry(tMs = 0, stage = 0x01, reason = 0x00, v1 = 0.9f, v2 = 0f, v3 = 2f),
                GestureDiagEntry(tMs = 6520, stage = 0x09, reason = 0x00, v1 = 175f, v2 = 1.1f, v3 = 502f),
            )
        )
        val lines = csv.trim().lines()
        assertTrue(lines[0].contains("milestones=2"))
        assertTrue(lines[1].startsWith("# milestone 0,0x01,0x00,"))
        assertTrue(lines[1].contains("outbound_start/none"))
        assertTrue(lines[2].startsWith("# milestone 6520,0x09,0x00,"))
        assertEquals("t_ms,flags,ax,ay,az,gx,gy,gz", lines[3])
    }

    @Test
    fun `csv omits both diag sources when neither is supplied`() {
        begin(result = 1, count = 1)
        GestureTrajectoryStore.onChunk(
            0,
            listOf(GestureTrajectorySample(0, 0, 1f, 2f, 3f, 0f, 0f, 0f)),
        )
        val lines = requireNotNull(GestureTrajectoryStore.onEnd(1, 0)).toCsv().trim().lines()
        assertTrue(lines[0].contains("milestones=0"))
        assertTrue(lines[0].contains("live=0"))
        assertEquals("t_ms,flags,ax,ay,az,gx,gy,gz", lines[1])
    }

    @Test
    fun `csv carries the live stream because the node batch is truncated`() {
        begin(result = 1, count = 1)
        GestureTrajectoryStore.onChunk(
            0,
            listOf(GestureTrajectorySample(0, 0, 1f, 2f, 3f, 0f, 0f, 0f)),
        )
        // Neither source shares the trajectory's t0 -- the node's history clock
        // starts at its first push after a clear -- so both go in labelled and
        // the offline pipeline segments from the samples themselves.
        val t = requireNotNull(GestureTrajectoryStore.onEnd(1, 0))
        val csv = t.toCsv(
            milestones = listOf(
                GestureDiagEntry(tMs = 0, stage = 0x0D, reason = 0x00, v1 = 104f, v2 = 7.07f, v3 = 1f),
            ),
            liveDiags = listOf(
                GestureDiagEntry(
                    tMs = 0, stage = 0x01, reason = 0x00, v1 = 0.9f, v2 = 0f, v3 = 2f,
                    receivedAtMs = 1_000L,
                ),
                GestureDiagEntry(
                    tMs = 0, stage = 0x09, reason = 0x00, v1 = 175f, v2 = 1.1f, v3 = 502f,
                    receivedAtMs = 1_548L,
                ),
            ),
        )
        val lines = csv.trim().lines()
        assertTrue(lines[0].contains("milestones=1"))
        assertTrue(lines[0].contains("live=2"))
        assertTrue(lines[1].startsWith("# milestone 0,0x0D,"))
        // Live entries are timed from the first of them, not from the node.
        assertTrue(lines[2].startsWith("# live 0,0x01,0x00,"))
        assertTrue(lines[2].contains("outbound_start/none"))
        assertTrue(lines[3].startsWith("# live 548,0x09,0x00,"))
        assertEquals("t_ms,flags,ax,ay,az,gx,gy,gz", lines[4])
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
        assertTrue(lines[0].contains("median_period_ms="))
        assertEquals("t_ms,flags,ax,ay,az,gx,gy,gz", lines[1])
        assertEquals(4, lines.size)
        assertTrue(lines[3].startsWith("25,7,"))
    }
}
