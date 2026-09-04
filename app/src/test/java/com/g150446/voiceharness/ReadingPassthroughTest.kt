package com.g150446.voiceharness

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingPassthroughTest {
    @Test
    fun `recognizes explicit Japanese and English reader mode commands`() {
        assertTrue(ReadingPassthrough.isRequested("リーダーモードに入って"))
        assertTrue(ReadingPassthrough.isRequested("Start reader mode"))
        assertTrue(ReadingPassthrough.isRequested("画面の本文をVuzixに表示して"))
    }

    @Test
    fun `does not recognize legacy passthrough phrasing`() {
        assertFalse(ReadingPassthrough.isRequested("パススルーモードに入って"))
        assertFalse(ReadingPassthrough.isRequested("Start passthrough mode"))
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
    fun `vertical writing maps to swipe right`() {
        val result = ReadingPassthrough.parseExtraction(
            """{"body_text":"縦の本文","writing_direction":"VERTICAL","page_turn_gesture":"UNKNOWN"}"""
        )
        assertEquals(WritingDirection.VERTICAL, result.writingDirection)
        assertEquals(PageTurnGesture.SWIPE_RIGHT, result.pageTurnGesture)
        assertEquals(
            PageTurnGesture.SWIPE_RIGHT,
            ReadingPassthrough.pageTurnGestureForWritingDirection(WritingDirection.VERTICAL),
        )
    }

    @Test
    fun `horizontal writing maps to swipe left`() {
        val result = ReadingPassthrough.parseExtraction(
            """{"body_text":"横の本文","writing_direction":"HORIZONTAL"}"""
        )
        assertEquals(WritingDirection.HORIZONTAL, result.writingDirection)
        assertEquals(PageTurnGesture.SWIPE_LEFT, result.pageTurnGesture)
        assertEquals(
            PageTurnGesture.SWIPE_LEFT,
            ReadingPassthrough.pageTurnGestureForWritingDirection(WritingDirection.HORIZONTAL),
        )
    }

    @Test
    fun `writing direction wins over conflicting page_turn_gesture`() {
        val result = ReadingPassthrough.parseExtraction(
            """{"body_text":"本文","writing_direction":"VERTICAL","page_turn_gesture":"SWIPE_LEFT"}"""
        )
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

    @Test
    fun `unknown preferred gesture tries both swipe directions`() {
        assertEquals(
            listOf(PageTurnGesture.SWIPE_LEFT, PageTurnGesture.SWIPE_RIGHT),
            pageTurnSwipeCandidates(PageTurnGesture.UNKNOWN),
        )
        assertEquals(
            listOf(PageTurnGesture.SWIPE_RIGHT),
            pageTurnSwipeCandidates(PageTurnGesture.SWIPE_RIGHT),
        )
    }
}
