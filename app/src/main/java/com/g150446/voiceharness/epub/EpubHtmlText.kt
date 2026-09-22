package com.g150446.voiceharness.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** Plain text of one chapter plus where each element `id` starts (for TOC fragment links). */
internal data class ChapterText(val text: String, val anchors: Map<String, Int>)

/** XHTML chapter → reading text for the phone and the glass. */
internal object EpubHtmlText {
    private val skipTags = setOf("script", "style", "head", "title", "rt", "rp", "img", "svg", "math", "audio", "video")
    private val paragraphTags = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "table", "figure")
    private val lineTags = setOf(
        "div", "li", "tr", "section", "article", "ul", "ol", "dt", "dd", "header", "footer", "aside", "nav", "hr",
    )

    fun extract(html: String): ChapterText {
        val document = Jsoup.parse(html)
        val out = Builder()
        document.body().let { walk(it, out, inPre = false) }
        return out.finish()
    }

    private fun walk(node: Node, out: Builder, inPre: Boolean) {
        when (node) {
            is TextNode -> out.text(node.wholeText, inPre)
            is Element -> {
                val tag = node.normalName()
                if (tag in skipTags) {
                    node.id().takeIf(String::isNotEmpty)?.let(out::anchor)
                    return
                }
                val breakLevel = when (tag) {
                    in paragraphTags -> 2
                    in lineTags -> 1
                    else -> 0
                }
                out.breakBefore(breakLevel)
                node.id().takeIf(String::isNotEmpty)?.let(out::anchor)
                if (tag == "br") {
                    out.hardNewline()
                    return
                }
                val pre = inPre || tag == "pre"
                node.childNodes().forEach { walk(it, out, pre) }
                out.breakAfter(breakLevel)
            }
        }
    }

    private class Builder {
        private val sb = StringBuilder()
        private val anchors = LinkedHashMap<String, Int>()
        private val pendingAnchors = ArrayList<String>()
        private var pendingBreak = 0

        fun anchor(id: String) {
            pendingAnchors += id
        }

        fun breakBefore(level: Int) {
            if (level > pendingBreak) pendingBreak = level
        }

        fun breakAfter(level: Int) {
            if (level > pendingBreak) pendingBreak = level
        }

        fun hardNewline() {
            flushBreak()
            if (sb.isNotEmpty()) {
                trimTrailingSpaces()
                sb.append('\n')
            }
        }

        fun text(raw: String, inPre: Boolean) {
            val cleaned = raw.replace(' ', ' ').replace("\r\n", "\n").replace('\r', '\n')
            val value = if (inPre) cleaned else collapseWhitespace(cleaned)
            if (value.isBlank() && (sb.isEmpty() || sb.last() == '\n' || pendingBreak > 0)) return
            flushBreak()
            val atLineStart = sb.isEmpty() || sb.last() == '\n'
            val appended = if (atLineStart && !inPre) value.trimStart(' ') else value
            if (appended.isEmpty()) return
            resolveAnchors()
            sb.append(appended)
        }

        fun finish(): ChapterText {
            resolveAnchors()
            val text = sb.toString().trimEnd()
            val clamped = anchors.mapValues { (_, offset) -> offset.coerceIn(0, text.length) }
            return ChapterText(text, clamped)
        }

        private fun flushBreak() {
            if (pendingBreak > 0 && sb.isNotEmpty()) {
                trimTrailingSpaces()
                var trailing = 0
                while (trailing < sb.length && sb[sb.length - 1 - trailing] == '\n') trailing += 1
                repeat((pendingBreak - trailing).coerceAtLeast(0)) { sb.append('\n') }
            }
            pendingBreak = 0
            resolveAnchors()
        }

        private fun resolveAnchors() {
            if (pendingAnchors.isEmpty()) return
            pendingAnchors.forEach { anchors.putIfAbsent(it, sb.length) }
            pendingAnchors.clear()
        }

        private fun trimTrailingSpaces() {
            while (sb.isNotEmpty() && sb.last() == ' ') sb.setLength(sb.length - 1)
        }

        private fun collapseWhitespace(value: String): String {
            val result = StringBuilder(value.length)
            var previousSpace = false
            for (ch in value) {
                if (ch == ' ' || ch == '\n' || ch == '\t') {
                    if (!previousSpace) result.append(' ')
                    previousSpace = true
                } else {
                    result.append(ch)
                    previousSpace = false
                }
            }
            return result.toString()
        }
    }
}
