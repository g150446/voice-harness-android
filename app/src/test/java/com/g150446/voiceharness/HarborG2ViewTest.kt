package com.g150446.voiceharness

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HarborG2ViewTest {
    @Test
    fun `live view keeps filtered terminal text`() {
        val view = parseHarborG2View(
            JSONObject(
                """{"view":"live","text":"\n────────\n実行中\n. . .\nerror: failed"}""",
            ),
        )

        assertFalse(view.summary)
        assertEquals("実行中\nerror: failed", view.text)
        assertEquals("", view.summaryText)
        assertEquals("", view.question)
        assertEquals(emptyList<String>(), view.options)
    }

    @Test
    fun `summary view keeps Japanese summary and verbatim options`() {
        val view = parseHarborG2View(
            JSONObject(
                """
                {
                  "view":"summary",
                  "summary":"テストまで完了しました。",
                  "question":"続行しますか？",
                  "options":["1. はい","2. いいえ",""]
                }
                """.trimIndent(),
            ),
        )

        assertTrue(view.summary)
        assertEquals("テストまで完了しました。", view.summaryText)
        assertEquals("続行しますか？", view.question)
        assertEquals(listOf("1. はい", "2. いいえ"), view.options)
        assertEquals("", view.text)
    }

    @Test
    fun `question-only summary is still a waiting view`() {
        val view = parseHarborG2View(
            JSONObject(
                """{"view":"summary","summary":"","question":"続行しますか？","options":["1. はい"]}""",
            ),
        )

        assertTrue(view.summary)
        assertEquals("", view.summaryText)
        assertEquals("続行しますか？", view.question)
        assertEquals(listOf("1. はい"), view.options)
    }

    @Test
    fun `confirmation speech reads only the AI response`() {
        assertEquals(
            "変更をコミットしてpushしますか？",
            harborConfirmationSpeech("変更をコミットしてpushしますか？"),
        )
        assertEquals(
            "どのブランチですか？",
            harborConfirmationSpeech("どのブランチですか？"),
        )
        assertEquals("", harborConfirmationSpeech(null))
    }

    @Test
    fun `work summary speech names Claude Code or Codex and includes its question`() {
        assertEquals(
            "Claude Codeの作業内容です。テストまで完了しました。" +
                "確認を求めています。続行しますか？選択肢は、1. はい、2. いいえです。",
            harborWorkSummarySpeech(
                HarborSpokenSummary(
                    workspaceId = "w-1",
                    agent = "claude",
                    summary = "テストまで完了しました。",
                    question = "続行しますか？",
                    options = listOf("1. はい", "2. いいえ"),
                ),
            ),
        )
        assertEquals(
            "Codexの作業内容です。実装とテストが完了しました。",
            harborWorkSummarySpeech(
                HarborSpokenSummary(
                    workspaceId = "w-2",
                    agent = "codex",
                    summary = "実装とテストが完了しました。",
                ),
            ),
        )
    }

    @Test
    fun `paused or cancelled Harbor poll does not publish`() {
        assertTrue(shouldPublishHarborPoll(paused = false, coroutineActive = true))
        assertFalse(shouldPublishHarborPoll(paused = true, coroutineActive = true))
        assertFalse(shouldPublishHarborPoll(paused = false, coroutineActive = false))
    }

    @Test
    fun `switch target resolves by name, directory casing and id`() {
        val workspaces = listOf(
            HarborWorkspace(id = "w-1", name = "voice-harness-even-g2", selected = true),
            HarborWorkspace(id = "w-2", name = "terminal-harbor", selected = false),
        )

        assertEquals("w-2", resolveHarborWorkspace("terminal-harbor", workspaces)?.id)
        assertEquals("w-2", resolveHarborWorkspace("Terminal Harbor", workspaces)?.id)
        assertEquals("w-1", resolveHarborWorkspace("w-1", workspaces)?.id)
        // Partial hits still resolve while they stay unique.
        assertEquals("w-1", resolveHarborWorkspace("even-g2", workspaces)?.id)
    }

    @Test
    fun `switch target refuses an empty, unknown or ambiguous name`() {
        val workspaces = listOf(
            HarborWorkspace(id = "w-1", name = "harbor-one", selected = true),
            HarborWorkspace(id = "w-2", name = "harbor-two", selected = false),
        )

        assertNull(resolveHarborWorkspace("", workspaces))
        assertNull(resolveHarborWorkspace("   ", workspaces))
        assertNull(resolveHarborWorkspace("nothing-like-this", workspaces))
        // "harbor" hits both, so the caller must be told rather than sent somewhere.
        assertNull(resolveHarborWorkspace("harbor", workspaces))
    }
}
