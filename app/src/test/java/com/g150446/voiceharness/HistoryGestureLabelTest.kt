package com.g150446.voiceharness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryGestureLabelTest {

    @Test
    fun `labels round-trip through storage`() {
        assertEquals(GestureLabel.ACCIDENTAL, GestureLabel.fromStorage("ACCIDENTAL"))
        assertEquals(GestureLabel.INTENTIONAL, GestureLabel.fromStorage("INTENTIONAL"))
    }

    @Test
    fun `unknown and absent labels read as unjudged`() {
        // An entry recorded before labelling existed must not become a training
        // example of either class.
        assertNull(GestureLabel.fromStorage(null))
        assertNull(GestureLabel.fromStorage(""))
        assertNull(GestureLabel.fromStorage("MAYBE"))
    }

    @Test
    fun `a fresh entry carries no trajectory and no label`() {
        val entry = HistoryEntry(
            id = "id",
            timestamp = 0L,
            transcription = "",
            response = "",
            isSilent = false,
            errorMessage = "",
        )
        assertNull(entry.trajectoryFile)
        assertNull(entry.gestureLabel)
    }
}
