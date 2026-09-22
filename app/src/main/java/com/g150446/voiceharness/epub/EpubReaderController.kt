package com.g150446.voiceharness.epub

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Where the reader publishes for the Even G2 glass. Fake in tests. */
internal interface EpubGlass {
    /** True while the glass should follow the reader (EPUB mode on and the plugin is polling). */
    fun following(): Boolean
    /** Reading text: the plugin paginates it and asks for the next chunk near its end. */
    fun publishReading(text: String)
    /** A short static page (outline, candidates, notices). */
    fun publishResponse(text: String)
    fun failAdvance(message: String)
}

internal data class EpubReaderState(
    val bookId: String? = null,
    val title: String = "",
    val author: String = "",
    val toc: List<TocEntry> = emptyList(),
    val view: EpubView = EpubView.TEXT,
    val chapter: Int = 0,
    /** Start / end offsets (in the chapter) of the chunk being shown. */
    val start: Int = 0,
    val end: Int = 0,
    /** The chunk as shown on the phone and sent to the glass. */
    val text: String = "",
    val candidates: List<Int> = emptyList(),
    val message: String? = null,
)

/**
 * Reading state for the EPUB mode, driven by the phone screens, the glass (advance requests) and
 * voice. The glass paginates on its own, so this only tracks the last chunk it was handed; "page"
 * commands therefore move by chunk (about 1,000 characters).
 */
internal class EpubReaderController(
    private val store: EpubStore,
    private val glass: EpubGlass,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val noticeHoldMs: Long = NOTICE_HOLD_MS,
) {
    private val _state = MutableStateFlow(EpubReaderState())
    val state: StateFlow<EpubReaderState> = _state.asStateFlow()

    private val lock = Mutex()
    private var book: EpubBook? = null
    private var generation = 0L

    fun hasLastBook(): Boolean = store.lastBookId() != null

    /** Titles for speech recognition hints (short, most recognisable first). */
    fun speechHints(): List<String> =
        _state.value.toc.map { it.title.trim() }.filter { it.length in 2..MAX_HINT_CHARS }.distinct().take(MAX_HINTS)

    /** Loads [id] and shows it from its saved position (or the start). */
    suspend fun openBook(id: String): Result<Unit> = lock.withLock {
        runCatching {
            loadBookLocked(id, resume = true)
            showTextLocked(_state.value.chapter, _state.value.start)
        }.onFailure { Log.w(TAG, "Open book failed: ${it.javaClass.simpleName}") }
    }

    /** EPUB mode was switched on (or the glass reconnected): show whatever the reader is on. */
    suspend fun enter() = lock.withLock {
        if (!ensureBookLocked()) {
            noticeLocked(NO_BOOK)
            return@withLock
        }
        republishLocked()
    }

    suspend fun republish() = lock.withLock { republishLocked() }

    suspend fun openToc() = lock.withLock { if (ensureBookLocked()) openTocLocked() else noticeLocked(NO_BOOK) }

    suspend fun openEntry(index: Int) = lock.withLock {
        if (ensureBookLocked()) openEntryLocked(index) else noticeLocked(NO_BOOK)
    }

    /** The glass asked for the text after the chunk it holds. Always ends in a publish or failAdvance. */
    suspend fun advanceFromGlass() = lock.withLock {
        val advanced = try {
            if (!ensureBookLocked()) {
                glass.failAdvance(NO_BOOK)
                return@withLock
            }
            stepLocked(1)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Advance failed: ${error.javaClass.simpleName}")
            glass.failAdvance(READ_FAILED)
            return@withLock
        }
        if (!advanced) glass.failAdvance(BOOK_END)
    }

    suspend fun next(count: Int = 1) = lock.withLock {
        if (ensureBookLocked() && !stepLocked(count)) noticeLocked(BOOK_END)
    }

    suspend fun previous(count: Int = 1) = lock.withLock {
        if (ensureBookLocked() && !stepLocked(-count)) noticeLocked(BOOK_START)
    }

    suspend fun nextChapter() = lock.withLock { if (ensureBookLocked()) chapterLocked(1) }

    suspend fun previousChapter() = lock.withLock { if (ensureBookLocked()) chapterLocked(-1) }

    /** Runs a spoken command; returns a short line for the phone status. */
    suspend fun handleVoice(raw: String): String = lock.withLock {
        try {
            handleVoiceLocked(raw)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Voice command failed: ${error.javaClass.simpleName}")
            noticeLocked(READ_FAILED)
            READ_FAILED
        }
    }

    private suspend fun handleVoiceLocked(raw: String): String {
        if (!ensureBookLocked()) {
            noticeLocked(NO_BOOK)
            return NO_BOOK
        }
        val current = _state.value
        val command = parseEpubCommand(raw, current.toc.map { it.title }, current.view, current.candidates)
        return when (command) {
            EpubVoiceCommand.OpenToc -> {
                openTocLocked()
                "目次を開きました"
            }
            is EpubVoiceCommand.OpenEntry -> {
                openEntryLocked(command.index)
                "「${current.toc[command.index].title}」を開きました"
            }
            is EpubVoiceCommand.Ambiguous -> {
                showCandidatesLocked(command.candidates)
                "候補が複数あります。番号で選んでください"
            }
            is EpubVoiceCommand.NextPage -> pageLocked(command.count)
            is EpubVoiceCommand.PrevPage -> pageLocked(-command.count)
            EpubVoiceCommand.NextChapter -> {
                chapterLocked(1)
                "次の章へ"
            }
            EpubVoiceCommand.PrevChapter -> {
                chapterLocked(-1)
                "前の章へ"
            }
            is EpubVoiceCommand.NotFound -> "「${command.heard}」に一致する項目がありません".also { noticeLocked(it) }
            EpubVoiceCommand.Unknown -> "聞き取れませんでした".also { noticeLocked(it) }
        }
    }

    // --- locked operations ---

    private suspend fun ensureBookLocked(): Boolean {
        if (book != null) return true
        val id = store.lastBookId() ?: return false
        return runCatching { loadBookLocked(id, resume = true) }
            .onFailure { Log.w(TAG, "Restore book failed: ${it.javaClass.simpleName}") }
            .isSuccess
    }

    private suspend fun loadBookLocked(id: String, resume: Boolean) {
        val opened = withContext(io) { store.openBook(id) }
        book = opened
        val saved = if (resume) store.position(id) else null
        val chapter = saved?.chapter?.takeIf { it in opened.spine.indices } ?: 0
        store.setLastBookId(id)
        _state.value = EpubReaderState(
            bookId = id,
            title = opened.title,
            author = opened.author,
            toc = opened.toc,
            chapter = chapter,
            start = if (saved?.chapter == chapter) saved.start else 0,
        )
    }

    /** Shows the chunk at [start] (skipping empty chapters) as a fresh reading page. */
    private suspend fun showTextLocked(chapter: Int, start: Int, notice: String? = null): Unit {
        val loaded = loadChunkLocked(chapter, start)
        if (loaded == null) {
            noticeLocked(BOOK_END)
            return
        }
        applyLocked(loaded, notice)
        if (glass.following()) glass.publishReading(loaded.display)
    }

    /** Moves by [count] chunks (negative = back). False when the book has no chunk that far. */
    private suspend fun stepLocked(count: Int): Boolean {
        val current = _state.value
        var chapter = current.chapter
        var start = current.start
        var end = if (current.view == EpubView.TEXT) current.end else current.start
        var loaded: Loaded? = null
        if (current.view != EpubView.TEXT) {
            // Paging away from the outline or the candidate list returns to the text where the reader was.
            loaded = loadChunkLocked(chapter, start)
        } else if (count > 0) {
            repeat(count) {
                loaded = loadChunkLocked(chapter, end) ?: return false
                chapter = loaded!!.chapter
                end = loaded!!.chunk.end
            }
        } else {
            repeat(-count) {
                loaded = previousChunkLocked(chapter, start) ?: return false
                chapter = loaded!!.chapter
                start = loaded!!.chunk.start
            }
        }
        val target = loaded ?: return false
        applyLocked(target, null)
        if (glass.following()) glass.publishReading(target.display)
        return true
    }

    private suspend fun pageLocked(count: Int): String {
        val moved = stepLocked(count)
        if (!moved) noticeLocked(if (count > 0) BOOK_END else BOOK_START)
        return if (count > 0) "次のページ" else "前のページ"
    }

    private suspend fun chapterLocked(delta: Int) {
        val opened = book ?: return
        var chapter = _state.value.chapter + delta
        while (chapter in opened.spine.indices) {
            val loaded = loadChunkLocked(chapter, 0)
            if (loaded != null && loaded.chapter == chapter) {
                applyLocked(loaded, null)
                if (glass.following()) glass.publishReading(loaded.display)
                return
            }
            chapter += delta
        }
        // No earlier chapter: from the middle of the first one, go to its start.
        if (delta < 0 && _state.value.start > 0) {
            showTextLocked(_state.value.chapter, 0)
            return
        }
        noticeLocked(if (delta > 0) BOOK_END else BOOK_START)
    }

    private suspend fun openTocLocked() {
        val opened = book ?: return
        if (opened.toc.isEmpty()) {
            noticeLocked("この本には目次がありません")
            return
        }
        generation += 1
        _state.update { it.copy(view = EpubView.TOC, candidates = emptyList(), message = null) }
        if (glass.following()) glass.publishResponse(tocGlassText(opened.toc))
    }

    private suspend fun openEntryLocked(index: Int) {
        val opened = book ?: return
        val entry = opened.toc.getOrNull(index) ?: return noticeLocked("その項目はありません")
        val offset = withContext(io) { opened.offsetOf(entry) }
        showTextLocked(entry.spineIndex, offset, notice = "「${entry.title}」を開きました")
    }

    private fun showCandidatesLocked(indexes: List<Int>) {
        val opened = book ?: return
        generation += 1
        _state.update { it.copy(view = EpubView.CANDIDATES, candidates = indexes, message = null) }
        if (glass.following()) glass.publishResponse(candidatesGlassText(indexes.map { opened.toc[it].title }))
    }

    private suspend fun republishLocked() {
        val opened = book
        if (opened == null) {
            if (glass.following()) glass.publishResponse(NO_BOOK)
            return
        }
        val current = _state.value
        generation += 1
        if (!glass.following()) return
        when (current.view) {
            EpubView.TOC -> glass.publishResponse(tocGlassText(opened.toc))
            EpubView.CANDIDATES ->
                glass.publishResponse(candidatesGlassText(current.candidates.map { opened.toc[it].title }))
            EpubView.TEXT -> {
                if (current.text.isNotEmpty()) glass.publishReading(current.text) else showTextLocked(current.chapter, current.start)
            }
        }
    }

    /** Shows a short notice on the glass, then puts the reader's page back. */
    private fun noticeLocked(message: String) {
        _state.update { it.copy(message = message) }
        if (!glass.following()) return
        val mine = ++generation
        glass.publishResponse(message)
        scope.launch {
            delay(noticeHoldMs)
            lock.withLock { if (generation == mine) republishLocked() }
        }
    }

    // --- chunk loading ---

    private class Loaded(val chapter: Int, val chunk: EpubChunk, val display: String)

    private suspend fun loadChunkLocked(chapter: Int, start: Int): Loaded? {
        val opened = book ?: return null
        var index = chapter
        var from = start
        while (index in opened.spine.indices) {
            val text = withContext(io) { opened.chapter(index).text }
            val chunk = EpubChunker.next(text, from)
            if (chunk != null) return Loaded(index, chunk, displayText(opened, index, chunk))
            index += 1
            from = 0
        }
        return null
    }

    private suspend fun previousChunkLocked(chapter: Int, start: Int): Loaded? {
        val opened = book ?: return null
        if (start > 0) {
            val text = withContext(io) { opened.chapter(chapter).text }
            EpubChunker.previous(text, start)?.let { return Loaded(chapter, it, displayText(opened, chapter, it)) }
        }
        var index = chapter - 1
        while (index >= 0) {
            val text = withContext(io) { opened.chapter(index).text }
            EpubChunker.last(text)?.let { return Loaded(index, it, displayText(opened, index, it)) }
            index -= 1
        }
        return null
    }

    private fun displayText(opened: EpubBook, chapter: Int, chunk: EpubChunk): String {
        if (chunk.start != 0) return chunk.text
        val title = opened.chapterTitle(chapter)?.trim().orEmpty()
        if (title.isEmpty() || normalizeForMatch(chunk.text).startsWith(normalizeForMatch(title))) return chunk.text
        return "■ $title\n\n${chunk.text}"
    }

    private fun applyLocked(loaded: Loaded, notice: String?) {
        generation += 1
        val id = _state.value.bookId
        _state.update {
            it.copy(
                view = EpubView.TEXT,
                chapter = loaded.chapter,
                start = loaded.chunk.start,
                end = loaded.chunk.end,
                text = loaded.display,
                candidates = emptyList(),
                message = notice,
            )
        }
        if (id != null) store.savePosition(id, EpubPosition(loaded.chapter, loaded.chunk.start))
    }

    companion object {
        private const val TAG = "EpubReader"
        const val NO_BOOK = "EPUBを開いてください"
        const val BOOK_END = "本の最後です"
        const val BOOK_START = "本の先頭です"
        const val READ_FAILED = "本文を読み込めませんでした"
        private const val NOTICE_HOLD_MS = 2_500L
        private const val MAX_HINTS = 24
        private const val MAX_HINT_CHARS = 24
        private const val GLASS_TITLE_CHARS = 36
        private const val ENTRIES_PER_PARAGRAPH = 4

        /**
         * Numbered outline for the glass. Entries are grouped four to a paragraph: the plugin's
         * pagination never splits a paragraph that fits a page, so an entry is not cut in half.
         */
        fun tocGlassText(toc: List<TocEntry>): String {
            val lines = toc.mapIndexed { index, entry ->
                val indent = "　".repeat(entry.depth.coerceAtMost(2))
                "${index + 1} $indent${entry.title.trim().take(GLASS_TITLE_CHARS)}"
            }
            val paragraphs = lines.chunked(ENTRIES_PER_PARAGRAPH).map { it.joinToString("\n") }
            return (listOf("目次（番号か題名で選ぶ）") + paragraphs).joinToString("\n\n")
        }

        fun candidatesGlassText(titles: List<String>): String {
            val lines = titles.mapIndexed { index, title -> "${index + 1} ${title.trim().take(GLASS_TITLE_CHARS)}" }
            return "候補（番号で選ぶ）\n\n" + lines.joinToString("\n")
        }
    }
}
