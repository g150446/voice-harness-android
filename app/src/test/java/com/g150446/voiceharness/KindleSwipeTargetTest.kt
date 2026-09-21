package com.g150446.voiceharness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KindleSwipeTargetTest {
    // Cover display of a foldable: not the default display, and much shorter than it.
    private val cover = SwipeTarget(displayId = 1, left = 0, top = 0, width = 1056, height = 1066)

    @Test
    fun `swipe stays inside the display Kindle is on`() {
        val line = pageTurnSwipeLine(PageTurnGesture.SWIPE_LEFT, cover)!!
        assertEquals(533f, line.y, 0.5f)
        assertEquals(true, line.y < cover.height)
        assertEquals(true, line.startX in 0f..cover.width.toFloat())
        assertEquals(true, line.endX in 0f..cover.width.toFloat())
    }

    @Test
    fun `left swipe moves right to left and right swipe moves left to right`() {
        val left = pageTurnSwipeLine(PageTurnGesture.SWIPE_LEFT, cover)!!
        val right = pageTurnSwipeLine(PageTurnGesture.SWIPE_RIGHT, cover)!!
        assertEquals(true, left.startX > left.endX)
        assertEquals(true, right.startX < right.endX)
    }

    @Test
    fun `swipe is offset by the window origin for a windowed Kindle`() {
        val windowed = SwipeTarget(displayId = 0, left = 100, top = 200, width = 1000, height = 800)
        val line = pageTurnSwipeLine(PageTurnGesture.SWIPE_RIGHT, windowed)!!
        assertEquals(280f, line.startX, 0.5f)
        assertEquals(920f, line.endX, 0.5f)
        assertEquals(600f, line.y, 0.5f)
    }

    @Test
    fun `unknown direction has no swipe`() {
        assertNull(pageTurnSwipeLine(PageTurnGesture.UNKNOWN, cover))
    }
}
