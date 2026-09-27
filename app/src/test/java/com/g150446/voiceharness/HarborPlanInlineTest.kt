package com.g150446.voiceharness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HarborPlanInlineTest {
    private val plan = HarborPlan(
        available = true,
        capability = "agent_file",
        agent = "Claude",
        text = "# Plan\n## Step 1\n## Step 2\n## 検証\n",
    )

    private val question =
        " Claude has written up a plan and is ready to execute. Would you like to proceed?"
    private val options = listOf(" ❯ 1. Yes, and auto-accept edits", "   2. No, keep planning")

    @Test
    fun `the rows under the header become the whole plan file`() {
        val screen = (
            listOf("> 前の指示", " Ready to code?", " Here is Claude's plan:", "╭────", "│ ## Step 2", "╰────") +
                question + options
            ).joinToString("\n")
        assertTrue(hasClaudePlanApproval(screen))
        assertEquals(
            (
                listOf(
                    "> 前の指示",
                    " Ready to code?",
                    " Here is Claude's plan:",
                    "── Claudeのプランファイル全文 ──",
                    "# Plan",
                    "## Step 1",
                    "## Step 2",
                    "## 検証",
                    "──",
                ) + question + options
                ).joinToString("\n"),
            inlineHarborPlan(screen, plan),
        )
    }

    @Test
    fun `a header that scrolled off is replaced from the top`() {
        val screen = (listOf("│ ## Step 2", "╰────") + question + options).joinToString("\n")
        assertEquals(
            (
                listOf("── Claudeのプランファイル全文 ──", "# Plan", "## Step 1", "## Step 2", "## 検証", "──") +
                    question + options
                ).joinToString("\n"),
            inlineHarborPlan(screen, plan),
        )
    }

    @Test
    fun `a wrapped question keeps both of its rows`() {
        val screen = listOf(
            " Here is Claude's plan:",
            "│ old rows",
            " Claude has written up a plan and is ready to execute.",
            " Would you like to proceed?",
            " ❯ 1. Yes",
        ).joinToString("\n")
        val shown = inlineHarborPlan(screen, plan).lines()
        assertEquals(
            listOf(
                "──",
                " Claude has written up a plan and is ready to execute.",
                " Would you like to proceed?",
                " ❯ 1. Yes",
            ),
            shown.takeLast(4),
        )
        assertFalse(shown.contains("│ old rows"))
    }

    @Test
    fun `without the prompt or a plan the screen is untouched`() {
        val plain = "$ ls\nREADME.md"
        assertFalse(hasClaudePlanApproval(plain))
        assertEquals(plain, inlineHarborPlan(plain, plan))
        val prompt = " Here is Claude's plan:\n│ rows\n$question"
        assertEquals(prompt, inlineHarborPlan(prompt, null))
        val none = HarborPlan(available = false, capability = "agent_file", reason = "plan_not_created")
        assertEquals(prompt, inlineHarborPlan(prompt, none))
    }
}
