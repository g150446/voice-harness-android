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
    private val glassesWords = listOf("グラス", "vuzix", "z100")
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
        Also determine how the user should swipe the Kindle screen to advance one page, based on
        the visible reader layout, writing direction, and book language. A SWIPE_LEFT gesture means
        the finger moves from the right side to the left side; SWIPE_RIGHT means left to right.
        Return exactly one JSON object with this schema:
        {"body_text":"visible body text","page_turn_gesture":"SWIPE_LEFT|SWIPE_RIGHT|UNKNOWN"}
        Put the body text verbatim in its original language and reading order, preserving paragraph
        breaks. Do not summarize, translate, paraphrase, explain, or add a title, preface, Markdown
        fence, page number, or any text not present in the body.
        Omit status bars, navigation, menus, buttons, reading progress, and other app chrome.
        If no readable body is available, use "$NO_READABLE_TEXT" for body_text.
        If the screenshot does not reveal a reliable page direction, use UNKNOWN. Never guess.
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
                val direction = when (json.optString("page_turn_gesture").uppercase()) {
                    PageTurnGesture.SWIPE_LEFT.name -> PageTurnGesture.SWIPE_LEFT
                    PageTurnGesture.SWIPE_RIGHT.name -> PageTurnGesture.SWIPE_RIGHT
                    else -> PageTurnGesture.UNKNOWN
                }
                return ReadingExtraction(usableExtractedText(body), direction)
            }
        }
        return ReadingExtraction(usableExtractedText(candidate), PageTurnGesture.UNKNOWN)
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
}

internal data class ReadingExtraction(
    val bodyText: String?,
    val pageTurnGesture: PageTurnGesture,
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
