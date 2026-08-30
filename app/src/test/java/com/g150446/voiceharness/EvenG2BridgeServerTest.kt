package com.g150446.voiceharness

import org.junit.Assert.assertEquals
import org.junit.Test

class EvenG2BridgeServerTest {
    @Test
    fun `double tap status is encoded for the Even G2 bridge`() {
        assertEquals(
            "{\"count\":7,\"lastDetectedAtMillis\":123456}",
            evenG2DoubleTapJson(DoubleTapStatus(count = 7, lastDetectedAtMillis = 123456L)),
        )
    }

    @Test
    fun `missing double tap timestamp is encoded as null`() {
        assertEquals(
            "{\"count\":0,\"lastDetectedAtMillis\":null}",
            evenG2DoubleTapJson(DoubleTapStatus()),
        )
    }

    @Test
    fun `reading state keeps Japanese text and revision`() {
        val json = evenG2ReadingJson(
            EvenG2ReadingSnapshot(
                enabled = true,
                active = true,
                revision = 4,
                bodyText = "吾輩は猫である。\n名前はまだ無い。",
                loading = false,
                error = null,
                doubleTapCount = 9,
            )
        )
        org.json.JSONObject(json).also {
            assertEquals(4, it.getLong("revision"))
            assertEquals("吾輩は猫である。\n名前はまだ無い。", it.getString("bodyText"))
            assertEquals(9, it.getLong("doubleTapCount"))
        }
    }
}
