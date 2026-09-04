package com.g150446.voiceharness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `loopback responses allow the Even Hub webview without caching`() {
        val headers = evenG2ResponseHeaders(200, "OK", 12)

        assertTrue(headers.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(headers.contains("Content-Length: 12\r\n"))
        assertTrue(headers.contains("Access-Control-Allow-Origin: *\r\n"))
        assertTrue(headers.contains("Access-Control-Allow-Private-Network: true\r\n"))
        assertTrue(headers.contains("Cache-Control: no-store\r\n"))
        assertTrue(headers.endsWith("\r\n\r\n"))
    }

    @Test
    fun `reading advance accepts only the current revision once`() {
        EvenG2ReadingSession.setEnabled(false)
        try {
            EvenG2ReadingSession.setEnabled(true)
            EvenG2ReadingSession.publishReading("本文")
            val revision = EvenG2ReadingSession.snapshot().revision

            assertEquals(202, EvenG2ReadingSession.beginAdvance(revision).code)
            assertEquals(409, EvenG2ReadingSession.beginAdvance(revision).code)
            EvenG2ReadingSession.failAdvance("retry")
            assertEquals(409, EvenG2ReadingSession.beginAdvance(revision - 1).code)
            assertEquals(202, EvenG2ReadingSession.beginAdvance(revision).code)
        } finally {
            EvenG2ReadingSession.setEnabled(false)
        }
    }
}
