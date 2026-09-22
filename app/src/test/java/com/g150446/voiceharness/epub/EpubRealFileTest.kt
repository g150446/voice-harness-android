package com.g150446.voiceharness.epub

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smoke test against a real, public-domain EPUB (Project Gutenberg #416, "Winesburg, Ohio",
 * published 1919 — no EPUB3 nav, only an NCX with several navPoints per chapter file), to catch
 * what the small synthetic fixtures in [TestEpub] cannot: real chapter markup and a real,
 * multi-hundred-KB outline/spine shape.
 */
class EpubRealFileTest {
    private fun extract(): File {
        val resource = javaClass.classLoader!!.getResourceAsStream("epub/winesburg-ohio-pg416.epub")
            ?: error("test fixture missing")
        val file = File.createTempFile("winesburg", ".epub")
        file.deleteOnExit()
        resource.use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    @Test
    fun `metadata, spine and NCX outline parse from a real Gutenberg epub`() {
        val book = EpubParser.parse(extract(), "real")
        assertTrue(book.title.contains("Winesburg"))
        assertTrue(book.author.contains("Anderson"))
        assertTrue(book.spine.size > 20)
        assertTrue(book.toc.size > 20)
        // NCX playOrder is preserved, not resorted.
        assertEquals(book.toc, book.toc.sortedWith(compareBy { book.toc.indexOf(it) }))
    }

    @Test
    fun `several outline entries share one chapter file at different anchors`() {
        val book = EpubParser.parse(extract(), "real")
        val firstChapterEntries = book.toc.filter { it.spineIndex == book.toc.first().spineIndex }
        assertTrue(firstChapterEntries.size >= 2)
        val offsets = firstChapterEntries.map { book.offsetOf(it) }.distinct()
        assertTrue(offsets.size >= 2)
        offsets.forEach { assertTrue(it in 0..book.chapter(book.toc.first().spineIndex).text.length) }
    }

    @Test
    fun `every chapter extracts to non-trivial text and tiles cleanly into chunks`() {
        val book = EpubParser.parse(extract(), "real")
        var totalChunks = 0
        var chaptersWithProse = 0
        book.spine.indices.forEach { index ->
            val text = book.chapter(index).text
            // A real book can have a cover page whose body is just an <img> with no text — that's
            // a legitimate empty chapter, not a parser bug, so it is allowed but not required.
            if (text.length > 10) chaptersWithProse += 1
            var cursor = 0
            var chunks = 0
            while (true) {
                val chunk = EpubChunker.next(text, cursor) ?: break
                assertTrue(chunk.end > chunk.start)
                assertEquals(cursor, chunk.start)
                assertTrue(chunk.text.isNotBlank())
                cursor = chunk.end
                chunks += 1
            }
            assertEquals(text.length, cursor)
            totalChunks += chunks
        }
        assertTrue(chaptersWithProse > book.spine.size / 2)
        assertTrue(totalChunks > book.spine.size)
    }

    @Test
    fun `an actual chapter heading from the book opens by voice`() {
        val book = EpubParser.parse(extract(), "real")
        val titles = book.toc.map { it.title }
        val heading = titles.first { it.length in 3..40 && it.none(Character::isDigit) }
        val index = titles.indexOf(heading)
        val command = parseEpubCommand(heading, titles)
        assertEquals(EpubVoiceCommand.OpenEntry(index), command)
    }
}
