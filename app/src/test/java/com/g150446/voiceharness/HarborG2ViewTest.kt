package com.g150446.voiceharness

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
