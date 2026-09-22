package com.g150446.voiceharness.epub

internal data class EpubChunk(val text: String, val start: Int, val end: Int)

/**
 * Splits a chapter into glass-sized chunks at paragraph boundaries. The glass plugin paginates a
 * chunk itself and asks for the next one near its end, so a chunk only has to be a few pages long.
 */
internal object EpubChunker {
    const val TARGET_CHARS = 1_000
    const val MAX_CHARS = 1_400
    private const val SENTENCE_ENDINGS = "。！？.!?"
    private const val CLOSING_MARKS = "\"'’”」』】〕）)]〉》"

    /** The chunk that starts at [start], or null at/after the end of [text]. */
    fun next(text: String, start: Int): EpubChunk? {
        val from = start.coerceAtLeast(0)
        if (from >= text.length) return null
        var end = from
        while (end < text.length) {
            val separator = text.indexOf("\n\n", end)
            val paragraphEnd = if (separator < 0) text.length else separator
            val nextStart = if (separator < 0) text.length else skipNewlines(text, separator)
            if (paragraphEnd - from > MAX_CHARS) {
                if (end == from) end = splitLong(text, from, paragraphEnd)
                break
            }
            end = nextStart
            if (paragraphEnd - from >= TARGET_CHARS) break
        }
        if (end <= from) end = minOf(text.length, from + MAX_CHARS)
        val chunk = text.substring(from, end).trim()
        return if (chunk.isEmpty()) next(text, end) else EpubChunk(chunk, from, end)
    }

    /** Start offsets of every chunk from the beginning of the chapter (the "grid" back-navigation uses). */
    fun starts(text: String): List<Int> {
        val result = ArrayList<Int>()
        var cursor = 0
        while (true) {
            val chunk = next(text, cursor) ?: break
            result += chunk.start
            cursor = chunk.end
        }
        return result
    }

    /** The chunk just before [start] (which may be an arbitrary offset, e.g. a TOC anchor), or null. */
    fun previous(text: String, start: Int): EpubChunk? {
        if (start <= 0) return null
        val previousStart = starts(text).lastOrNull { it < start } ?: return null
        val chunk = next(text, previousStart) ?: return null
        val end = minOf(chunk.end, start)
        val trimmed = text.substring(previousStart, end).trim()
        return if (trimmed.isEmpty()) null else EpubChunk(trimmed, previousStart, end)
    }

    /** The last chunk of a chapter, for stepping back across a chapter boundary. */
    fun last(text: String): EpubChunk? {
        val lastStart = starts(text).lastOrNull() ?: return null
        return next(text, lastStart)
    }

    private fun skipNewlines(text: String, from: Int): Int {
        var index = from
        while (index < text.length && text[index] == '\n') index += 1
        return index
    }

    private fun splitLong(text: String, start: Int, paragraphEnd: Int): Int {
        val limit = minOf(paragraphEnd, start + MAX_CHARS)
        var best = -1
        var index = start + TARGET_CHARS / 2
        while (index < limit) {
            if (text[index] in SENTENCE_ENDINGS) {
                var end = index + 1
                while (end < limit && text[end] in CLOSING_MARKS) end += 1
                best = end
            }
            index += 1
        }
        var end = if (best > 0) best else limit
        if (end < text.length && end > start + 1 && Character.isHighSurrogate(text[end - 1])) end -= 1
        return end
    }
}
