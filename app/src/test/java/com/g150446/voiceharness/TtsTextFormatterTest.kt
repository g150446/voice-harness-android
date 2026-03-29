package com.g150446.voiceharness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsTextFormatterTest {

    @Test
    fun toSpeakableChunks_removesMarkdownFormatting() {
        val chunks = TtsTextFormatter.toSpeakableChunks(
            text = "## Title\n* **Bold** item\n1. Step one\n| a | b |",
            maxLength = 200
        )

        assertEquals(1, chunks.size)
        assertEquals("Title Bold item Step one a b", chunks.first())
    }

    @Test
    fun toSpeakableChunks_splitsLongTextIntoBoundedChunks() {
        val text = List(8) { "This is a long sentence for speech output." }.joinToString(" ")

        val chunks = TtsTextFormatter.toSpeakableChunks(text, maxLength = 60)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 60 })
    }
}
