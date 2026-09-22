package com.g150446.voiceharness.epub

import com.g150446.voiceharness.parseReaderPageCommand
import java.text.Normalizer

/** What the reader is showing; decides how a bare number is read. */
internal enum class EpubView { TEXT, TOC, CANDIDATES }

internal sealed interface EpubVoiceCommand {
    data object OpenToc : EpubVoiceCommand
    /** [index] indexes the book's outline (0-based). */
    data class OpenEntry(val index: Int) : EpubVoiceCommand
    /** Several outline entries fit about equally; the glass lists them (outline indexes, best first). */
    data class Ambiguous(val candidates: List<Int>) : EpubVoiceCommand
    data class NextPage(val count: Int) : EpubVoiceCommand
    data class PrevPage(val count: Int) : EpubVoiceCommand
    data object NextChapter : EpubVoiceCommand
    data object PrevChapter : EpubVoiceCommand
    data class NotFound(val heard: String) : EpubVoiceCommand
    data object Unknown : EpubVoiceCommand
}

private const val ACCEPT_SCORE = 0.75
private const val CANDIDATE_SCORE = 0.5
private const val CLEAR_MARGIN = 0.1
private const val CANDIDATE_BAND = 0.2
private const val MAX_CANDIDATES = 5

private val FILLER_SUFFIX = Regex(
    "(?:[をにへ])?(?:開いて|開く|開け|ひらいて|ひらく|表示して|表示|見せて|みせて|読んで|よんで|" +
        "移動して|移動|飛んで|とんで|行って|いって|出して|おねがいします|お願いします|お願い|おねがい|" +
        "くださいませ|ください|して)+$",
)
private val ORDINAL = Regex("^第?(\\d+)(?:番目?|ばん(?:め)?)(?:の?(?:項目|章|節))?$")
private val BARE_NUMBER = Regex("^(\\d+)$")
private val CHAPTER_NUMBER = Regex("第?\\d+章")
private val SHORT_NEXT = setOf("次", "つぎ", "つぎへ", "進む", "すすむ", "進めて", "すすめて", "次へ")
private val SHORT_BACK = setOf("前", "まえ", "前へ", "戻る", "もどる", "戻って", "もどって", "戻して", "もどして")
private val TOC_WORDS = listOf("目次", "もくじ", "アウトライン", "あうとらいん", "toc")

/**
 * Parses one spoken utterance in EPUB mode. [titles] are the outline titles in order (their
 * position is the 0-based outline index, shown to the reader as index + 1). [candidates] are the
 * outline indexes listed on the glass when [view] is [EpubView.CANDIDATES].
 */
internal fun parseEpubCommand(
    raw: String,
    titles: List<String>,
    view: EpubView = EpubView.TEXT,
    candidates: List<Int> = emptyList(),
): EpubVoiceCommand {
    val heard = raw.trim()
    if (heard.isEmpty()) return EpubVoiceCommand.Unknown
    val normalized = normalizeForMatch(heard)
    if (normalized.isEmpty()) return EpubVoiceCommand.Unknown
    val query = stripFillers(normalized)

    if (TOC_WORDS.any { normalized.contains(normalizeForMatch(it)) }) return EpubVoiceCommand.OpenToc

    if (query.contains('章') && !CHAPTER_NUMBER.containsMatchIn(query)) {
        if (Regex("次|つぎ|進|すすめ|すすむ").containsMatchIn(query)) return EpubVoiceCommand.NextChapter
        if (Regex("前|まえ|戻|もど").containsMatchIn(query)) return EpubVoiceCommand.PrevChapter
    }

    val number = (ORDINAL.find(query) ?: BARE_NUMBER.takeIf { view != EpubView.TEXT }?.find(query))
        ?.groupValues?.get(1)?.toIntOrNull()
    if (number != null) return openByNumber(number, heard, titles.size, view, candidates)

    if (normalized.contains("ぺーじ") || normalized.contains("ページ") || normalized.contains("page")) {
        parseReaderPageCommand(heard)?.let {
            return if (it.forward) EpubVoiceCommand.NextPage(it.pages) else EpubVoiceCommand.PrevPage(it.pages)
        }
    }

    if (query.isNotEmpty() && titles.isNotEmpty()) {
        matchTitle(query, titles)?.let { return it }
    }

    return when (normalized) {
        in SHORT_NEXT.map(::normalizeForMatch) -> EpubVoiceCommand.NextPage(1)
        in SHORT_BACK.map(::normalizeForMatch) -> EpubVoiceCommand.PrevPage(1)
        else -> if (query.isEmpty() || titles.isEmpty()) EpubVoiceCommand.Unknown else EpubVoiceCommand.NotFound(heard)
    }
}

private fun openByNumber(
    number: Int,
    heard: String,
    outlineSize: Int,
    view: EpubView,
    candidates: List<Int>,
): EpubVoiceCommand {
    if (view == EpubView.CANDIDATES) {
        return candidates.getOrNull(number - 1)?.let(EpubVoiceCommand::OpenEntry)
            ?: EpubVoiceCommand.NotFound(heard)
    }
    return if (number in 1..outlineSize) EpubVoiceCommand.OpenEntry(number - 1) else EpubVoiceCommand.NotFound(heard)
}

private fun matchTitle(query: String, titles: List<String>): EpubVoiceCommand? {
    val scored = titles.mapIndexed { index, title -> index to titleScore(normalizeForMatch(title), query) }
        .filter { it.second > 0.0 }
        .sortedWith(compareByDescending<Pair<Int, Double>> { it.second }.thenBy { it.first })
    val top = scored.firstOrNull() ?: return null
    val exact = scored.filter { it.second >= 1.0 }
    if (exact.size == 1) return EpubVoiceCommand.OpenEntry(exact.single().first)
    val second = scored.getOrNull(1)
    if (top.second >= ACCEPT_SCORE && (second == null || top.second - second.second >= CLEAR_MARGIN)) {
        return EpubVoiceCommand.OpenEntry(top.first)
    }
    if (top.second >= CANDIDATE_SCORE) {
        val floor = maxOf(CANDIDATE_SCORE, top.second - CANDIDATE_BAND)
        val picked = scored.filter { it.second >= floor }.take(MAX_CANDIDATES).map { it.first }
        return if (picked.size == 1) EpubVoiceCommand.OpenEntry(picked.single()) else EpubVoiceCommand.Ambiguous(picked)
    }
    return null
}

/** 1.0 exact, 0.9+ when the title contains the query, 0.85 when the query contains the title, else bigram Dice. */
internal fun titleScore(title: String, query: String): Double {
    if (title.isEmpty() || query.isEmpty()) return 0.0
    if (title == query) return 1.0
    if (query.length >= 2 && title.contains(query)) return 0.9 + 0.09 * query.length / title.length
    if (title.length >= 3 && query.contains(title)) return 0.85
    return bigramDice(title, query)
}

private fun bigramDice(a: String, b: String): Double {
    if (a.length < 2 || b.length < 2) return 0.0
    fun bigrams(value: String) = (0 until value.length - 1).map { value.substring(it, it + 2) }
    val left = bigrams(a)
    val remaining = bigrams(b).toMutableList()
    var shared = 0
    for (pair in left) if (remaining.remove(pair)) shared += 1
    return 2.0 * shared / (left.size + bigrams(b).size)
}

private fun stripFillers(normalized: String): String {
    var current = normalized
    while (true) {
        val next = FILLER_SUFFIX.replace(current, "")
        if (next == current) return current
        current = next
    }
}

/**
 * Comparison form: NFKC, lower case, kanji numerals as digits, katakana as hiragana, and only
 * letters/digits kept (spaces and punctuation vary between the outline and speech recognition).
 */
internal fun normalizeForMatch(value: String): String {
    val folded = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase()
    val numbered = kanjiNumerals(folded)
    val out = StringBuilder(numbered.length)
    for (ch in numbered) {
        val unified = if (ch in 'ァ'..'ヶ') ch - 0x60 else ch
        if (Character.isLetterOrDigit(unified)) out.append(unified)
    }
    return out.toString()
}

private val KANJI_DIGITS = mapOf(
    '〇' to 0, '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
)
private val KANJI_RUN = Regex("[〇一二三四五六七八九十百]+")

private fun kanjiNumerals(value: String): String = KANJI_RUN.replace(value) { match ->
    val run = match.value
    // A lone 一/二… inside a word ("一般", "二人") is still converted consistently on both sides.
    kanjiRunToInt(run).toString()
}

private fun kanjiRunToInt(run: String): Int {
    if (run.none { it == '十' || it == '百' }) return run.fold(0) { acc, ch -> acc * 10 + (KANJI_DIGITS[ch] ?: 0) }
    var total = 0
    var current = 0
    for (ch in run) {
        when (ch) {
            '百' -> { total += (if (current == 0) 1 else current) * 100; current = 0 }
            '十' -> { total += (if (current == 0) 1 else current) * 10; current = 0 }
            else -> current = KANJI_DIGITS[ch] ?: 0
        }
    }
    return total + current
}
