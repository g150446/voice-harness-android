package com.g150446.voiceharness

/**
 * Terminal Harbor mirrors a desktop terminal, so its horizontal rules are as wide as that
 * terminal (often well past 100 columns). The phone renders the screen text with ordinary
 * soft wrapping, which turns one rule into three or four display rows and buries the output
 * it was meant to separate.
 *
 * [collapseRuleRuns] shortens runs of rule characters so a separator occupies one row at the
 * width the caller actually has. Vertical bars and corners are deliberately left alone: they
 * carry table structure, and shrinking them would misalign the very box they draw.
 */
internal object HarborTextLayout {
    /** Horizontal rule characters only — no verticals (│┃┆┇┊┋╎╏) and no corners/junctions. */
    private const val RULE_CHARS = "─━═┄┅┈┉╌╍-=_~‐‑‒–—―·⋅⋯…"

    /** Runs shorter than this are prose (「---」「…」), not a separator. */
    const val MIN_RULE_RUN = 8

    /** A collapsed run still has to read as a rule. */
    const val MIN_KEPT_RUN = 4

    /** Used when the caller could not measure a real column count. */
    const val FALLBACK_COLUMNS = 40

    fun collapseRuleRuns(text: String, maxColumns: Int): String {
        if (text.isEmpty()) return text
        val columns = if (maxColumns > 0) maxColumns else FALLBACK_COLUMNS
        val builder = StringBuilder(text.length)
        var index = 0
        while (index <= text.length) {
            val newline = text.indexOf('\n', index)
            val end = if (newline == -1) text.length else newline
            builder.append(collapseLine(text.substring(index, end), columns))
            if (newline == -1) break
            builder.append('\n')
            index = newline + 1
        }
        return builder.toString()
    }

    private fun collapseLine(line: String, columns: Int): String {
        val runs = ruleRuns(line)
        val collapsible = runs.filter { it.length >= MIN_RULE_RUN }
        if (collapsible.isEmpty()) return line

        // Everything that is not going to be shortened has to fit first: plain text, and the
        // short rule runs we leave as they are.
        val fixedWidth = line.length - collapsible.sumOf { it.length }
        val budget = columns - fixedWidth
        val perRun = if (budget <= 0) {
            MIN_KEPT_RUN
        } else {
            maxOf(MIN_KEPT_RUN, budget / collapsible.size)
        }

        val builder = StringBuilder(line.length)
        var cursor = 0
        for (run in collapsible) {
            builder.append(line, cursor, run.start)
            val kept = minOf(run.length, perRun)
            repeat(kept) { builder.append(line[run.start]) }
            cursor = run.start + run.length
        }
        builder.append(line, cursor, line.length)
        return builder.toString()
    }

    private data class RuleRun(val start: Int, val length: Int)

    /** Maximal runs of a single repeated rule character. `─━─` is three runs, not one. */
    private fun ruleRuns(line: String): List<RuleRun> {
        val runs = mutableListOf<RuleRun>()
        var index = 0
        while (index < line.length) {
            val char = line[index]
            if (RULE_CHARS.indexOf(char) == -1) {
                index++
                continue
            }
            var end = index + 1
            while (end < line.length && line[end] == char) end++
            runs += RuleRun(index, end - index)
            index = end
        }
        return runs
    }
}
