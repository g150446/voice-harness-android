package com.g150446.voiceharness.epub

import java.io.Closeable
import java.io.File
import java.util.zip.ZipFile

internal class EpubException(message: String) : Exception(message)

/** One outline entry. [spineIndex] indexes [EpubBook.spine]; [anchor] is an element id in that chapter. */
internal data class TocEntry(
    val title: String,
    val depth: Int,
    val spineIndex: Int,
    val anchor: String?,
)

internal data class SpineItem(val path: String, val id: String)

/** A parsed EPUB: metadata, reading order and outline. Chapter text is read lazily from the file. */
internal class EpubBook(
    val id: String,
    val title: String,
    val author: String,
    val spine: List<SpineItem>,
    val toc: List<TocEntry>,
    private val file: File,
) {
    private val cache = object : LinkedHashMap<Int, ChapterText>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ChapterText>?): Boolean = size > 4
    }

    @Synchronized
    fun chapter(index: Int): ChapterText {
        require(index in spine.indices) { "章がありません: $index" }
        cache[index]?.let { return it }
        val html = ZipFile(file).use { zip ->
            val entry = zip.getEntry(spine[index].path)
                ?: throw EpubException("章のファイルが見つかりません: ${spine[index].path}")
            zip.getInputStream(entry).use { String(it.readBytes(), Charsets.UTF_8) }
        }
        return EpubHtmlText.extract(html).also { cache[index] = it }
    }

    /** Title to show above a chapter: the first outline entry that points at its start. */
    fun chapterTitle(index: Int): String? =
        toc.firstOrNull { it.spineIndex == index && it.anchor == null }?.title
            ?: toc.firstOrNull { it.spineIndex == index }?.title

    /** Offset of an outline entry inside its chapter (0 when it has no anchor or the anchor is unknown). */
    fun offsetOf(entry: TocEntry): Int = entry.anchor?.let { chapter(entry.spineIndex).anchors[it] } ?: 0
}

/** Zip access shared by the parser (kept small so tests can build archives in memory-backed temp files). */
internal class EpubArchive(file: File) : Closeable {
    private val zip = try {
        ZipFile(file)
    } catch (e: Exception) {
        throw EpubException("EPUBファイルを開けません")
    }

    fun has(path: String): Boolean = zip.getEntry(path) != null

    fun read(path: String): String? {
        val entry = zip.getEntry(path) ?: return null
        return zip.getInputStream(entry).use { String(it.readBytes(), Charsets.UTF_8) }
    }

    override fun close() = zip.close()
}
