package com.g150446.voiceharness

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The node's 0x33-0x35 batch is the only milestone source whose `tMs` shares an
 * origin with the trajectory, so claiming it correctly is what makes the raw
 * window segmentable.
 */
class GestureDiagBatchTest {

    @After
    fun tearDown() = GestureDiagStore.clear()

    private fun deliverBatch(session: Int = 1, count: Int = 2) {
        GestureDiagStore.onHistoryBegin(count, session)
        GestureDiagStore.onHistoryEntry(0, 0x01, 0x00, 0.9f, 0f, 2f)
        GestureDiagStore.onHistoryEntry(6520, 0x09, 0x00, 175f, 1.1f, 502f)
        GestureDiagStore.onHistoryEnd(count, session)
    }

    @Test
    fun `a batch that arrives just after the stop is claimed`() {
        deliverBatch()
        val batch = GestureDiagStore.takeBatchForRecording(System.currentTimeMillis())
        assertNotNull(batch)
        assertEquals(2, batch!!.size)
        assertEquals(6520, batch[1].tMs)
    }

    @Test
    fun `a batch is claimed once`() {
        deliverBatch()
        val now = System.currentTimeMillis()
        assertNotNull(GestureDiagStore.takeBatchForRecording(now))
        // Attaching one batch to two entries would duplicate the milestones and
        // leave the second recording claiming timings it never produced.
        assertNull(GestureDiagStore.takeBatchForRecording(now))
    }

    @Test
    fun `a stale batch is not claimed`() {
        deliverBatch()
        assertNull(GestureDiagStore.takeBatchForRecording(System.currentTimeMillis() - 60_000L))
    }

    @Test
    fun `a zero stop time claims nothing`() {
        deliverBatch()
        assertNull(GestureDiagStore.takeBatchForRecording(0L))
    }

    @Test
    fun `no batch means no claim`() {
        assertNull(GestureDiagStore.takeBatchForRecording(System.currentTimeMillis()))
    }

    @Test
    fun `the live slice keeps its own clock and is unaffected`() {
        val now = System.currentTimeMillis()
        GestureDiagStore.onLiveDiag(0x01, 0x00, 0.9f, 0f, 2f)
        GestureDiagStore.onLiveDiag(0x09, 0x00, 175f, 1.1f, 502f)
        val slice = GestureDiagStore.snapshotForRecording(now, now + 1_000L)
        assertEquals(2, slice.size)
        // Renumbered from the first entry, i.e. the phone's receive clock — which
        // is exactly why it must not be written into the trajectory CSV.
        assertEquals(0, slice.first().tMs)
    }
}
