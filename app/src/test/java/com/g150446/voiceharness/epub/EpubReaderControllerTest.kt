package com.g150446.voiceharness.epub

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EpubReaderControllerTest {
    private class FakeGlass : EpubGlass {
        var following = true
        val reading = mutableListOf<String>()
        val responses = mutableListOf<String>()
        val failures = mutableListOf<String>()
        override fun following() = following
        override fun publishReading(text: String) { reading += text }
        override fun publishResponse(text: String) { responses += text }
        override fun failAdvance(message: String) { failures += message }
    }

    private class FakeStore : EpubStore {
        val books = HashMap<String, EpubBook>()
        val positions = HashMap<String, EpubPosition>()
        var last: String? = null
        override fun openBook(id: String) = books[id] ?: throw EpubException("本が見つかりません")
        override fun lastBookId() = last
        override fun setLastBookId(id: String?) { last = id }
        override fun position(id: String) = positions[id]
        override fun savePosition(id: String, position: EpubPosition) { positions[id] = position }
    }

    private class Fixture(scope: TestScope, book: EpubBook = EpubParser.parse(TestEpub.long(), "b1")) {
        val glass = FakeGlass()
        val store = FakeStore().apply { books["b1"] = book }
        val controller = EpubReaderController(
            store = store,
            glass = glass,
            scope = scope,
            io = UnconfinedTestDispatcher(scope.testScheduler),
            noticeHoldMs = 1_000L,
        )
    }

    @Test
    fun `opening a book shows its first chunk with the chapter title and saves the position`() = runTest {
        val f = Fixture(this)
        assertTrue(f.controller.openBook("b1").isSuccess)
        val state = f.controller.state.value
        assertEquals(EpubView.TEXT, state.view)
        assertEquals(0, state.chapter)
        assertEquals(1, f.glass.reading.size)
        assertTrue(f.glass.reading.single().startsWith("■ 第一章 序\n\n"))
        assertEquals("b1", f.store.last)
        assertEquals(EpubPosition(0, 0), f.store.positions["b1"])
    }

    @Test
    fun `the glass advance walks the chunks, crosses into the next chapter, then reports the end`() = runTest {
        val f = Fixture(this)
        f.controller.openBook("b1")
        val chapterOneChunks = EpubChunker.starts(EpubParser.parse(TestEpub.long(), "x").chapter(0).text).size
        repeat(chapterOneChunks - 1) { f.controller.advanceFromGlass() }
        assertEquals(0, f.controller.state.value.chapter)
        f.controller.advanceFromGlass()
        assertEquals(1, f.controller.state.value.chapter)
        assertTrue(f.glass.reading.last().startsWith("■ 第二章 本編"))
        assertTrue(f.glass.failures.isEmpty())
        f.controller.advanceFromGlass()
        assertEquals(listOf(EpubReaderController.BOOK_END), f.glass.failures)
    }

    @Test
    fun `previous steps back across a chapter boundary to the last chunk`() = runTest {
        val f = Fixture(this)
        f.controller.openBook("b1")
        f.controller.nextChapter()
        assertEquals(1, f.controller.state.value.chapter)
        f.controller.previous()
        val state = f.controller.state.value
        assertEquals(0, state.chapter)
        assertTrue(state.start > 0)
        f.controller.previousChapter()
        assertEquals(0, f.controller.state.value.start)
        f.controller.previous()
        assertEquals(EpubReaderController.BOOK_START, f.controller.state.value.message)
    }

    @Test
    fun `the outline is shown numbered and a spoken number opens that entry at its anchor`() = runTest {
        val f = Fixture(this)
        f.controller.openBook("b1")
        f.controller.openToc()
        assertEquals(EpubView.TOC, f.controller.state.value.view)
        val toc = f.glass.responses.last()
        assertTrue(toc.contains("1 第一章 序"))
        assertTrue(toc.contains("3 あとがき"))

        val said = f.controller.handleVoice("3番")
        assertEquals("「あとがき」を開きました", said)
        val state = f.controller.state.value
        assertEquals(EpubView.TEXT, state.view)
        assertEquals(1, state.chapter)
        assertTrue(f.glass.reading.last().contains("あとがき"))
        assertFalse(f.glass.reading.last().contains("本編の冒頭"))
    }

    @Test
    fun `a spoken title opens its entry`() = runTest {
        val f = Fixture(this)
        f.controller.openBook("b1")
        f.controller.handleVoice("第二章を開いて")
        assertEquals(1, f.controller.state.value.chapter)
        assertEquals(0, f.controller.state.value.start)
    }

    @Test
    fun `an ambiguous title lists numbered candidates and the next number picks one`() = runTest {
        val f = Fixture(this)
        f.controller.openBook("b1")
        f.controller.handleVoice("参考")
        assertEquals(EpubView.CANDIDATES, f.controller.state.value.view)
        assertEquals(listOf(3, 4), f.controller.state.value.candidates)
        assertTrue(f.glass.responses.last().contains("1 参考文献"))
        assertTrue(f.glass.responses.last().contains("2 参考資料"))

        f.controller.handleVoice("2番")
        val state = f.controller.state.value
        assertEquals(EpubView.TEXT, state.view)
        assertTrue(f.glass.reading.last().contains("資料"))
        assertFalse(f.glass.reading.last().contains("参考文献"))
    }

    @Test
    fun `an unknown title shows a notice and then puts the reading page back`() = runTest {
        val f = Fixture(this)
        f.controller.openBook("b1")
        val page = f.glass.reading.last()
        val message = f.controller.handleVoice("宇宙戦争を開いて")
        assertTrue(message.contains("一致する項目がありません"))
        assertEquals(message, f.glass.responses.last())
        assertEquals(1, f.glass.reading.size)

        advanceTimeBy(1_100)
        runCurrent()
        assertEquals(2, f.glass.reading.size)
        assertEquals(page, f.glass.reading.last())
    }

    @Test
    fun `a newer page cancels the pending notice restore`() = runTest {
        val f = Fixture(this)
        f.controller.openBook("b1")
        f.controller.handleVoice("宇宙戦争を開いて")
        f.controller.next()
        val shown = f.glass.reading.size
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals(shown, f.glass.reading.size)
    }

    @Test
    fun `paging away from the outline returns to the text`() = runTest {
        val f = Fixture(this)
        f.controller.openBook("b1")
        f.controller.openToc()
        f.controller.handleVoice("次のページ")
        assertEquals(EpubView.TEXT, f.controller.state.value.view)
        assertEquals(0, f.controller.state.value.start)
    }

    @Test
    fun `voice next and back move by chunk`() = runTest {
        val f = Fixture(this)
        f.controller.openBook("b1")
        f.controller.handleVoice("次のページ")
        val second = f.controller.state.value.start
        assertTrue(second > 0)
        f.controller.handleVoice("前のページ")
        assertEquals(0, f.controller.state.value.start)
    }

    @Test
    fun `nothing is published while the glass is not following but the state still moves`() = runTest {
        val f = Fixture(this)
        f.glass.following = false
        f.controller.openBook("b1")
        f.controller.next()
        assertTrue(f.glass.reading.isEmpty())
        assertTrue(f.glass.responses.isEmpty())
        assertTrue(f.controller.state.value.start > 0)
    }

    @Test
    fun `the saved position is restored when the book is opened again`() = runTest {
        val f = Fixture(this)
        f.controller.openBook("b1")
        f.controller.next(2)
        val saved = f.store.positions["b1"]!!
        val again = EpubReaderController(
            f.store, f.glass, this, UnconfinedTestDispatcher(testScheduler), 1_000L,
        )
        again.openBook("b1")
        assertEquals(saved.start, again.state.value.start)
        assertEquals(saved.chapter, again.state.value.chapter)
    }

    @Test
    fun `enter restores the last book and reports when there is none`() = runTest {
        val f = Fixture(this)
        f.controller.enter()
        assertEquals(EpubReaderController.NO_BOOK, f.glass.responses.last())

        f.store.last = "b1"
        f.controller.enter()
        assertEquals("b1", f.controller.state.value.bookId)
        assertEquals(1, f.glass.reading.size)
    }

    @Test
    fun `a glass advance with no book fails the advance instead of leaving it loading`() = runTest {
        val f = Fixture(this)
        f.controller.advanceFromGlass()
        assertEquals(listOf(EpubReaderController.NO_BOOK), f.glass.failures)
    }

    @Test
    fun `a book that cannot be opened reports failure`() = runTest {
        val f = Fixture(this)
        assertTrue(f.controller.openBook("missing").isFailure)
    }

    @Test
    fun `speech hints are the outline titles`() = runTest {
        val f = Fixture(this)
        f.controller.openBook("b1")
        assertNotNull(f.controller.speechHints().firstOrNull { it == "あとがき" })
    }

    @Test
    fun `glass text for the outline groups entries into paragraphs of four`() {
        val toc = (1..6).map { TocEntry("章$it", if (it == 2) 1 else 0, 0, null) }
        val text = EpubReaderController.tocGlassText(toc)
        val paragraphs = text.split("\n\n")
        assertEquals("目次（番号か題名で選ぶ）", paragraphs[0])
        assertEquals(4, paragraphs[1].lines().size)
        assertEquals(2, paragraphs[2].lines().size)
        assertTrue(paragraphs[1].lines()[1].startsWith("2 　章2"))
    }
}
