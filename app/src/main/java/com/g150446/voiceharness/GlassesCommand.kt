package com.g150446.voiceharness

import java.util.Locale

internal const val GLASSES_MODE_SWITCH_PHRASE = "グラスモード変更"
internal const val READER_PAGE_COMMAND_MAX = 20
internal const val HARBOR_CONFIRM_TIMEOUT_MS = 15_000L
/** Terminal Harbor mirror poll period; each tick repaints the glass. */
internal const val HARBOR_MIRROR_POLL_MS = 1_000L
/** How long a Harbor result stays on the glass before the mirror may repaint over it. */
internal const val HARBOR_RESULT_HOLD_MS = 4_000L
internal const val HARBOR_CONFIRM_STT_MAX = 80

internal fun harborConfirmPrompt(
    stt: String,
    aiComment: String? = null,
    awaitingClarification: Boolean = false,
): String = buildString {
    append("確認\n")
    append(stt.take(HARBOR_CONFIRM_STT_MAX))
    if (stt.length > HARBOR_CONFIRM_STT_MAX) append("…")
    aiComment?.takeIf { it.isNotBlank() }?.let { append("\n\nAI: ").append(it) }
    append(
        if (awaitingClarification) {
            "\n\nダブルタップで言い直す"
        } else {
            "\n\nシングルタップで実行\nダブルタップで取り消す"
        },
    )
}

internal data class ReaderPageCommand(val pages: Int, val forward: Boolean)

internal fun glassesModeSwitchRemainder(text: String): String? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val match = Regex("^グラス\\s*モード\\s*変更[\\s、。,.!！?？・:：_-]*").find(trimmed)
        ?: return null
    return trimmed.substring(match.range.last + 1).trim()
}

internal fun parseReaderPageCommand(text: String): ReaderPageCommand? {
    val compact = text.lowercase(Locale.ROOT).replace(Regex("[\\s　]+"), "")
    if (compact.isEmpty()) return null
    val backward = listOf("戻", "前", "まえ", "back").any(compact::contains)
    val forwardHint = listOf("進", "次", "つぎ", "めく", "forward", "next").any(compact::contains)
    val count = parseSpokenCount(compact)
    if (!backward && !forwardHint && count == null) return null
    if (!backward && !forwardHint && !compact.contains("ページ") && !compact.contains("page")) {
        return null
    }
    return ReaderPageCommand(
        pages = (count ?: 1).coerceIn(1, READER_PAGE_COMMAND_MAX),
        forward = !backward,
    )
}

internal fun parseSpokenCount(text: String): Int? {
    Regex("(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
    val fullWidth = text.map { ch ->
        if (ch in '０'..'９') '0' + (ch - '０') else ch
    }.joinToString("")
    Regex("(\\d+)").find(fullWidth)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
    return KANJI_COUNTS.entries
        .sortedByDescending { it.key.length }
        .firstOrNull { text.contains(it.key) }
        ?.value
}

private val KANJI_COUNTS = mapOf(
    "二十" to 20, "十九" to 19, "十八" to 18, "十七" to 17, "十六" to 16,
    "十五" to 15, "十四" to 14, "十三" to 13, "十二" to 12, "十一" to 11,
    "十" to 10, "九" to 9, "八" to 8, "七" to 7, "六" to 6,
    "五" to 5, "四" to 4, "三" to 3, "二" to 2, "一" to 1,
)
