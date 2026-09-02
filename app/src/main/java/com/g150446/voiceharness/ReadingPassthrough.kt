package com.g150446.voiceharness

import org.json.JSONObject

/** Voice intent and text handling for the smart-glasses reading passthrough mode. */
internal object ReadingPassthrough {
    const val NO_READABLE_TEXT = "[NO_READABLE_TEXT]"

    private val explicitCommands = listOf(
        Regex("パス[・ ー-]*スルー(?:モード)?", RegexOption.IGNORE_CASE),
        Regex("(?:passthrough|pass[ -]?through)(?: mode)?", RegexOption.IGNORE_CASE),
    )

    private val screenWords = listOf("画面", "表示内容", "本文", "電子書籍", "kindle", "本")
    private val glassesWords = listOf("グラス", "g2", "even", "vuzix", "z100")
    private val displayWords = listOf("表示", "映して", "見せて", "送って", "読む", "読書")

    fun isRequested(query: String): Boolean {
        val normalized = query.trim().lowercase()
        if (normalized.isEmpty()) return false
        if (explicitCommands.any { it.containsMatchIn(normalized) }) return true
        return screenWords.any(normalized::contains) &&
            glassesWords.any(normalized::contains) &&
            displayWords.any(normalized::contains)
    }

    fun extractionPrompt(userCommand: String): String = """
        Enter reading passthrough mode for the user's command: "$userCommand"
        Extract the main ebook or article body that is visible in the supplied screen context.
        Also judge the visible writing direction of that body:
        - VERTICAL = tategaki / columns top-to-bottom, lines progressing right-to-left
        - HORIZONTAL = yokogaki / lines left-to-right
        - UNKNOWN only when the screenshot does not reveal a reliable direction. Never guess.
        Map writing direction to the Kindle page-turn finger swipe with these fixed rules:
        - VERTICAL → SWIPE_RIGHT (finger moves left to right)
        - HORIZONTAL → SWIPE_LEFT (finger moves right to left)
        - UNKNOWN writing direction → page_turn_gesture UNKNOWN
        SWIPE_LEFT means the finger moves from the right side to the left side.
        SWIPE_RIGHT means the finger moves from the left side to the right side.
        Return exactly one JSON object with this schema:
        {"body_text":"visible body text","writing_direction":"VERTICAL|HORIZONTAL|UNKNOWN","page_turn_gesture":"SWIPE_LEFT|SWIPE_RIGHT|UNKNOWN"}
        Put the body text verbatim in its original language and reading order, preserving paragraph
        breaks. Do not summarize, translate, paraphrase, explain, or add a title, preface, Markdown
        fence, page number, or any text not present in the body.
        Omit status bars, navigation, menus, buttons, reading progress, and other app chrome.
        If no readable body is available, use "$NO_READABLE_TEXT" for body_text.
    """.trimIndent()

    internal fun parseExtraction(raw: String): ReadingExtraction {
        val candidate = raw.trim()
            .removeSurrounding("```json\n", "\n```")
            .removeSurrounding("```\n", "\n```")
            .trim()
        if (candidate.startsWith("{")) {
            runCatching {
                val json = JSONObject(candidate)
                val body = json.optString("body_text", json.optString("text", ""))
                val writing = parseWritingDirection(json.optString("writing_direction"))
                val gestureFromWriting = pageTurnGestureForWritingDirection(writing)
                val gestureFromField = parsePageTurnGesture(json.optString("page_turn_gesture"))
                val gesture = when {
                    gestureFromWriting != PageTurnGesture.UNKNOWN -> gestureFromWriting
                    else -> gestureFromField
                }
                return ReadingExtraction(
                    bodyText = usableExtractedText(body),
                    pageTurnGesture = gesture,
                    writingDirection = writing,
                )
            }
        }
        return ReadingExtraction(
            bodyText = usableExtractedText(candidate),
            pageTurnGesture = PageTurnGesture.UNKNOWN,
            writingDirection = WritingDirection.UNKNOWN,
        )
    }

    fun usableExtractedText(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == NO_READABLE_TEXT) return null
        return trimmed
            .removeSurrounding("```text\n", "\n```")
            .removeSurrounding("```\n", "\n```")
            .trim()
            .takeIf { it.isNotEmpty() && it != NO_READABLE_TEXT }
    }

    internal fun parseWritingDirection(raw: String?): WritingDirection =
        when (raw?.trim()?.uppercase()) {
            WritingDirection.VERTICAL.name, "TATEGAKI", "縦書き", "縦" -> WritingDirection.VERTICAL
            WritingDirection.HORIZONTAL.name, "YOKOGAKI", "横書き", "横" -> WritingDirection.HORIZONTAL
            else -> WritingDirection.UNKNOWN
        }

    internal fun pageTurnGestureForWritingDirection(direction: WritingDirection): PageTurnGesture =
        when (direction) {
            WritingDirection.VERTICAL -> PageTurnGesture.SWIPE_RIGHT
            WritingDirection.HORIZONTAL -> PageTurnGesture.SWIPE_LEFT
            WritingDirection.UNKNOWN -> PageTurnGesture.UNKNOWN
        }

    private fun parsePageTurnGesture(raw: String?): PageTurnGesture =
        when (raw?.trim()?.uppercase()) {
            PageTurnGesture.SWIPE_LEFT.name -> PageTurnGesture.SWIPE_LEFT
            PageTurnGesture.SWIPE_RIGHT.name -> PageTurnGesture.SWIPE_RIGHT
            else -> PageTurnGesture.UNKNOWN
        }
}

internal data class ReadingExtraction(
    val bodyText: String?,
    val pageTurnGesture: PageTurnGesture,
    val writingDirection: WritingDirection = WritingDirection.UNKNOWN,
)

internal data class ReadingPageRange(
    val firstLine: Int,
    val lineCount: Int,
)

/** Calculates pages from rendered Z100 line slices, never from Android character counts. */
internal object ReadingPageLayout {
    fun linesPerPage(screenHeightPx: Int, sliceHeightPx: Int): Int {
        require(screenHeightPx > 0) { "screenHeightPx must be positive" }
        require(sliceHeightPx > 0) { "sliceHeightPx must be positive" }
        return (screenHeightPx / sliceHeightPx).coerceAtLeast(1)
    }

    fun pageCount(totalLines: Int, linesPerPage: Int): Int {
        require(totalLines >= 0) { "totalLines must not be negative" }
        require(linesPerPage > 0) { "linesPerPage must be positive" }
        return if (totalLines == 0) 0 else (totalLines + linesPerPage - 1) / linesPerPage
    }

    fun pageRange(pageIndex: Int, totalLines: Int, linesPerPage: Int): ReadingPageRange {
        val count = pageCount(totalLines, linesPerPage)
        require(pageIndex in 0 until count) { "pageIndex is outside the document" }
        val firstLine = pageIndex * linesPerPage
        return ReadingPageRange(
            firstLine = firstLine,
            lineCount = minOf(linesPerPage, totalLines - firstLine),
        )
    }
}
