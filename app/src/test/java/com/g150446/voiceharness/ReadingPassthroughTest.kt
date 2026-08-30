package com.g150446.voiceharness

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingPassthroughTest {
    @Test
    fun `recognizes explicit Japanese and English passthrough commands`() {
        assertTrue(ReadingPassthrough.isRequested("パススルーモードに入って"))
        assertTrue(ReadingPassthrough.isRequested("Start passthrough mode"))
        assertTrue(ReadingPassthrough.isRequested("画面の本文をVuzixに表示して"))
    }

    @Test
    fun `does not hijack an ordinary screen question`() {
        assertFalse(ReadingPassthrough.isRequested("この画面を要約して"))
    }

    @Test
    fun `rejects the no-readable-text sentinel`() {
        assertNull(ReadingPassthrough.usableExtractedText(ReadingPassthrough.NO_READABLE_TEXT))
    }

    @Test
    fun `page capacity is calculated from the Z100 pixel height`() {
        assertEquals(10, ReadingPageLayout.linesPerPage(screenHeightPx = 480, sliceHeightPx = 48))
    }

    @Test
    fun `rendered lines produce complete page ranges without gaps`() {
        assertEquals(2, ReadingPageLayout.pageCount(totalLines = 11, linesPerPage = 10))
        assertEquals(ReadingPageRange(0, 10), ReadingPageLayout.pageRange(0, 11, 10))
        assertEquals(ReadingPageRange(10, 1), ReadingPageLayout.pageRange(1, 11, 10))
    }

    @Test
    fun `empty rendered text has no pages`() {
        assertEquals(0, ReadingPageLayout.pageCount(totalLines = 0, linesPerPage = 10))
    }

    @Test
    fun `structured extraction preserves body and VLM swipe direction`() {
        val result = ReadingPassthrough.parseExtraction(
            """{"body_text":"本文です。","page_turn_gesture":"SWIPE_RIGHT"}"""
        )
        assertEquals("本文です。", result.bodyText)
        assertEquals(PageTurnGesture.SWIPE_RIGHT, result.pageTurnGesture)
    }

    @Test
    fun `unknown or legacy extraction never guesses a swipe direction`() {
        assertEquals(
            PageTurnGesture.UNKNOWN,
            ReadingPassthrough.parseExtraction("本文だけ").pageTurnGesture,
        )
        assertEquals(
            PageTurnGesture.UNKNOWN,
            ReadingPassthrough.parseExtraction(
                "{\"body_text\":\"本文\",\"page_turn_gesture\":\"maybe\"}"
            ).pageTurnGesture,
        )
    }
}
