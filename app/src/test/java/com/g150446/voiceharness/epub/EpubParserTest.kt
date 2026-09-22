package com.g150446.voiceharness.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EpubParserTest {
    @Test
    fun `epub3 reads metadata, spine without the nav document, and a nested outline`() {
        val book = EpubParser.parse(TestEpub.epub3(), "id1")
        assertEquals("吾輩は猫である", book.title)
        assertEquals("夏目漱石", book.author)
        assertEquals(listOf("text/c1.xhtml", "text/c2.xhtml").map { "OEBPS/$it" }, book.spine.map { it.path })
        assertEquals(
            listOf(
                TocEntry("はじめに", 0, 0, null),
                TocEntry("第一章 出会い", 0, 1, null),
                TocEntry("第一節 猫の名前", 1, 1, "s2"),
            ),
            book.toc,
        )
    }

    @Test
    fun `an outline entry with a fragment jumps to that element in the chapter`() {
        val book = EpubParser.parse(TestEpub.epub3(), "id1")
        val entry = book.toc.last()
        val chapter = book.chapter(entry.spineIndex)
        val offset = book.offsetOf(entry)
        assertTrue(offset > 0)
        assertTrue(chapter.text.substring(offset).startsWith("第一節"))
        assertEquals(0, book.offsetOf(book.toc.first()))
    }

    @Test
    fun `epub2 falls back to the NCX and decodes percent-encoded paths`() {
        val book = EpubParser.parse(TestEpub.epub2(), "id2")
        assertEquals("Old Book", book.title)
        assertEquals(listOf("OEBPS/a b.xhtml", "OEBPS/b.xhtml"), book.spine.map { it.path })
        assertEquals(
            listOf(TocEntry("Part One", 0, 0, null), TocEntry("Chapter B", 1, 1, "top")),
            book.toc,
        )
        assertEquals("Alpha", book.chapter(0).text)
    }

    @Test
    fun `chapter title comes from the outline entry that starts the chapter`() {
        val book = EpubParser.parse(TestEpub.epub3(), "id1")
        assertEquals("はじめに", book.chapterTitle(0))
        assertEquals("第一章 出会い", book.chapterTitle(1))
    }

    @Test
    fun `DRM protected books are rejected with a Japanese message`() {
        try {
            EpubParser.parse(TestEpub.drm(), "d")
            fail("expected EpubException")
        } catch (e: EpubException) {
            assertTrue(e.message!!.contains("DRM"))
        }
    }

    @Test
    fun `a file that is not an epub is rejected`() {
        val file = java.io.File.createTempFile("bad", ".epub").apply { writeText("not a zip"); deleteOnExit() }
        try {
            EpubParser.parse(file, "x")
            fail("expected EpubException")
        } catch (e: EpubException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun `an epub without a container is rejected`() {
        val file = TestEpub.write(mapOf("hello.txt" to "hi"))
        try {
            EpubParser.parse(file, "x")
            fail("expected EpubException")
        } catch (e: EpubException) {
            assertTrue(e.message!!.contains("EPUB"))
        }
    }

    @Test
    fun `resolve collapses dot segments relative to the base file`() {
        assertEquals("OEBPS/text/c1.xhtml", EpubParser.resolve("OEBPS/nav.xhtml", "text/c1.xhtml"))
        assertEquals("OEBPS/c.xhtml", EpubParser.resolve("OEBPS/text/x.ncx", "../c.xhtml#f"))
        assertEquals("c.xhtml", EpubParser.resolve("content.opf", "./c.xhtml"))
    }
}
