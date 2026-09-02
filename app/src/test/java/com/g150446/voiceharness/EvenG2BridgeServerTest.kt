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
                mode = EvenG2DisplayMode.READING,
                revision = 4,
                bodyText = "吾輩は猫である。\n名前はまだ無い。",
                loading = false,
                error = null,
                doubleTapCount = 9,
                singleTapCount = 3,
            )
        )
        org.json.JSONObject(json).also {
            assertEquals(4, it.getLong("revision"))
            assertEquals("reading", it.getString("mode"))
            assertEquals("吾輩は猫である。\n名前はまだ無い。", it.getString("bodyText"))
            assertEquals(9, it.getLong("doubleTapCount"))
            assertEquals(3, it.getLong("singleTapCount"))
        }
    }

    @Test
    fun `response mode is encoded for AI answers`() {
        val json = evenG2ReadingJson(
            EvenG2ReadingSnapshot(
                enabled = false,
                active = true,
                mode = EvenG2DisplayMode.RESPONSE,
                revision = 2,
                bodyText = "こんにちは",
                loading = false,
                error = null,
                doubleTapCount = 0,
                singleTapCount = 1,
            )
        )
        org.json.JSONObject(json).also {
            assertEquals("response", it.getString("mode"))
            assertEquals("こんにちは", it.getString("bodyText"))
            assertEquals(true, it.getBoolean("active"))
            assertEquals(1, it.getLong("singleTapCount"))
        }
    }
}
