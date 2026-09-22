package com.g150446.voiceharness

import org.junit.Assert.assertEquals
import org.junit.Test

class HarborTextLayoutTest {
    @Test
    fun `rule-only line is shortened to the available columns`() {
        val line = "─".repeat(120)

        assertEquals("─".repeat(40), HarborTextLayout.collapseRuleRuns(line, 40))
    }

    @Test
    fun `box border keeps its corners`() {
        val line = "╭" + "─".repeat(118) + "╮"

        val collapsed = HarborTextLayout.collapseRuleRuns(line, 40)

        assertEquals("╭" + "─".repeat(38) + "╮", collapsed)
        assertEquals(40, collapsed.length)
    }

    @Test
    fun `table divider splits the width between its runs`() {
        val line = "├" + "─".repeat(50) + "┼" + "─".repeat(50) + "┤"

        val collapsed = HarborTextLayout.collapseRuleRuns(line, 40)

        assertEquals("├" + "─".repeat(18) + "┼" + "─".repeat(18) + "┤", collapsed)
    }

    @Test
    fun `short runs in prose are left alone`() {
        val text = "--- 見出し ---\n…\n=== done ==="

        assertEquals(text, HarborTextLayout.collapseRuleRuns(text, 40))
    }

    @Test
    fun `a run of mixed rule characters is collapsed per character`() {
        val line = "─".repeat(30) + "━".repeat(30)

        val collapsed = HarborTextLayout.collapseRuleRuns(line, 40)

        assertEquals("─".repeat(20) + "━".repeat(20), collapsed)
    }

    @Test
    fun `a crowded line still keeps a readable stub`() {
        val line = "結果は以上です " + "─".repeat(60)

        val collapsed = HarborTextLayout.collapseRuleRuns(line, 8)

        assertEquals("結果は以上です " + "─".repeat(HarborTextLayout.MIN_KEPT_RUN), collapsed)
    }

    @Test
    fun `line structure and trailing newline survive`() {
        val text = "a\n" + "─".repeat(50) + "\n\nb\n"

        val collapsed = HarborTextLayout.collapseRuleRuns(text, 20)

        assertEquals("a\n" + "─".repeat(20) + "\n\nb\n", collapsed)
    }

    @Test
    fun `a non-positive column count falls back instead of erasing rules`() {
        val line = "─".repeat(90)

        assertEquals(
            "─".repeat(HarborTextLayout.FALLBACK_COLUMNS),
            HarborTextLayout.collapseRuleRuns(line, 0),
        )
    }

    @Test
    fun `empty text is returned unchanged`() {
        assertEquals("", HarborTextLayout.collapseRuleRuns("", 40))
    }
}
