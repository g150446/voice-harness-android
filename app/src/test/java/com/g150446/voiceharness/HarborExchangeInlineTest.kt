package com.g150446.voiceharness

import org.junit.Assert.assertEquals
import org.junit.Test

class HarborExchangeInlineTest {
    private fun user(text: String, cursor: String) = HarborTranscriptMessage("user", text, cursor = cursor)
    private fun assistant(text: String, cursor: String) = HarborTranscriptMessage("assistant", text, cursor = cursor)

    private val screen = "│ 応答の末尾\n❯ "

    @Test
    fun `only the last instruction and the replies after it head the screen`() {
        val messages = listOf(
            user("前の指示", "1"),
            assistant("前の応答", "2"),
            user("テストを直して\n全部", "3"),
            assistant("調べます", "4"),
            assistant("直しました\n応答の末尾\n", "5"),
        )
        assertEquals(
            listOf(
                "── 直前の指示とClaudeの応答（会話ログ全文）──",
                "> テストを直して",
                "> 全部",
                "",
                "調べます",
                "",
                "直しました",
                "応答の末尾",
                "── ここから現在の画面 ──",
                "│ 応答の末尾",
                "❯ ",
            ).joinToString("\n"),
            prependHarborExchange(screen, messages, "Claude"),
        )
    }

    @Test
    fun `an image size note is not taken for the instruction`() {
        val note = "[Image: original 1080x2640, displayed at 818x2000. Multiply coordinates by 1.32 to map to original image.]"
        val shown = prependHarborExchange(
            screen,
            listOf(user("画面を確認して", "1"), assistant("見ます", "2"), user(note, "3"), assistant("確認しました", "4")),
            "Claude",
        ).lines()
        assertEquals("> 画面を確認して", shown[1])
        assertEquals(false, shown.any { it.contains("Image: original") })
        assertEquals("確認しました", shown[shown.indexOf("── ここから現在の画面 ──") - 1])
    }

    @Test
    fun `without an instruction loaded the screen is untouched`() {
        assertEquals(screen, prependHarborExchange(screen, emptyList(), "Claude"))
        assertEquals(screen, prependHarborExchange(screen, listOf(assistant("途中から", "9")), "Claude"))
    }

    @Test
    fun `the plan approval question still ends the view`() {
        val plan = HarborPlan(available = true, capability = "agent_file", agent = "Claude", text = "# Plan\n")
        val approval = listOf(
            " Here is Claude's plan:",
            "│ rows",
            " Claude has written up a plan and is ready to execute. Would you like to proceed?",
            " ❯ 1. Yes",
        ).joinToString("\n")
        val shown = prependHarborExchange(
            inlineHarborPlan(approval, plan),
            listOf(user("プランを立てて", "1"), assistant("プランです", "2")),
            "Claude",
        ).lines()
        assertEquals("> プランを立てて", shown[1])
        assertEquals(" ❯ 1. Yes", shown.last())
        assertEquals("# Plan", shown[shown.indexOf("── Claudeのプランファイル全文 ──") + 1])
    }
}
