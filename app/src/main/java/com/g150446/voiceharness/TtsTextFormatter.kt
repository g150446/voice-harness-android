package com.g150446.voiceharness

object TtsTextFormatter {

    fun toSpeakableChunks(text: String, maxLength: Int): List<String> {
        val normalized = normalize(text)
        if (normalized.isBlank()) {
            return emptyList()
        }

        val chunks = mutableListOf<String>()
        var currentChunk = StringBuilder()

        for (segment in splitSegments(normalized)) {
            if (segment.length > maxLength) {
                flushChunk(currentChunk, chunks)
                splitLongSegment(segment, maxLength, chunks)
                continue
            }

            val separatorLength = if (currentChunk.isEmpty()) 0 else 1
            if (currentChunk.length + separatorLength + segment.length > maxLength) {
                flushChunk(currentChunk, chunks)
            }

            if (currentChunk.isNotEmpty()) {
                currentChunk.append(' ')
            }
            currentChunk.append(segment)
        }

        flushChunk(currentChunk, chunks)
        return chunks
    }

    private fun normalize(text: String): String =
        text
            .replace(Regex("\\[(.*?)]\\((.*?)\\)"), "$1")
            .replace("**", "")
            .replace("__", "")
            .replace("`", "")
            .replace(Regex("(?m)^#{1,6}\\s*"), "")
            .replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
            .replace(Regex("(?m)^\\s*\\d+\\.\\s+"), "")
            .replace('|', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun splitSegments(text: String): List<String> =
        text
            .split(Regex("(?<=[.!?。！？:;])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun splitLongSegment(segment: String, maxLength: Int, chunks: MutableList<String>) {
        var start = 0
        while (start < segment.length) {
            val end = minOf(start + maxLength, segment.length)
            chunks += segment.substring(start, end).trim()
            start = end
        }
    }

    private fun flushChunk(currentChunk: StringBuilder, chunks: MutableList<String>) {
        if (currentChunk.isNotEmpty()) {
            chunks += currentChunk.toString().trim()
            currentChunk.setLength(0)
        }
    }
}
