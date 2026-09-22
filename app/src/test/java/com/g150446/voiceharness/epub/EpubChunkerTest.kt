package com.g150446.voiceharness.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubChunkerTest {
    private fun paragraph(n: Int, size: Int) = "${n}: " + "あ".repeat(size)

    @Test
    fun `empty and finished chapters have no chunk`() {
        assertNull(EpubChunker.next("", 0))
        assertNull(EpubChunker.next("abc", 3))
    }

    @Test
    fun `short chapters are a single chunk`() {
        val chunk = EpubChunker.next("一\n\n二", 0)!!
        assertEquals("一\n\n二", chunk.text)
        assertEquals(0, chunk.start)
        assertEquals(4, chunk.end)
    }

    @Test
    fun `paragraphs are gathered until the target and never split when they fit`() {
        val text = (1..6).joinToString("\n\n") { paragraph(it, 400) }
        val first = EpubChunker.next(text, 0)!!
        assertTrue(first.text.length in EpubChunker.TARGET_CHARS..EpubChunker.MAX_CHARS)
        assertTrue(first.text.endsWith("あ"))
        assertTrue(text.startsWith(first.text))
        assertEquals(3, first.text.split("\n\n").size)
    }

    @Test
    fun `chunks tile the chapter without gaps or overlaps`() {
        val text = (1..20).joinToString("\n\n") { paragraph(it, 250) }
        var cursor = 0
        val pieces = ArrayList<String>()
        while (true) {
            val chunk = EpubChunker.next(text, cursor) ?: break
            assertTrue(chunk.end > chunk.start)
            assertEquals(cursor, chunk.start)
            pieces += chunk.text
            cursor = chunk.end
        }
        assertEquals(text.length, cursor)
        assertEquals(text, pieces.joinToString("\n\n"))
    }

    @Test
    fun `an overlong paragraph is split after a sentence ending`() {
        val text = "あ".repeat(600) + "。" + "い".repeat(2_000)
        val chunk = EpubChunker.next(text, 0)!!
        assertTrue(chunk.text.endsWith("。"))
        assertTrue(chunk.text.length <= EpubChunker.MAX_CHARS)
    }

    @Test
    fun `an overlong paragraph without punctuation is hard split and still progresses`() {
        val text = "あ".repeat(5_000)
        val chunk = EpubChunker.next(text, 0)!!
        assertEquals(EpubChunker.MAX_CHARS, chunk.end)
        assertEquals(5_000 / EpubChunker.MAX_CHARS + 1, EpubChunker.starts(text).size)
    }

    @Test
    fun `a hard split never cuts a surrogate pair`() {
        val text = "😀".repeat(2_000)
        val chunk = EpubChunker.next(text, 0)!!
        assertTrue(!Character.isHighSurrogate(text[chunk.end - 1]))
    }

    @Test
    fun `previous returns the grid chunk before an arbitrary offset`() {
        val text = (1..12).joinToString("\n\n") { paragraph(it, 300) }
        val starts = EpubChunker.starts(text)
        assertTrue(starts.size >= 3)
        val previous = EpubChunker.previous(text, starts[2])!!
        assertEquals(starts[1], previous.start)
        assertNull(EpubChunker.previous(text, 0))
        // An anchor in the middle of a chunk: the previous chunk is cut at the anchor.
        val anchor = starts[1] + 50
        val cut = EpubChunker.previous(text, anchor)!!
        assertEquals(starts[1], cut.start)
        assertEquals(anchor, cut.end)
    }

    @Test
    fun `last returns the final chunk`() {
        val text = (1..12).joinToString("\n\n") { paragraph(it, 300) }
        val last = EpubChunker.last(text)!!
        assertEquals(EpubChunker.starts(text).last(), last.start)
        assertEquals(text.length, last.end)
    }
}
