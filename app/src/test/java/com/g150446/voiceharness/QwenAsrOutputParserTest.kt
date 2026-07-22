package com.g150446.voiceharness

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class QwenAsrOutputParserTest {
    @Test
    fun parsesJapaneseOutput() {
        val result = QwenAsrOutputParser.parse(
            "warning line\nlanguage Japanese<asr_text>明日の朝七時に起こして。\n\n"
        )

        assertEquals("明日の朝七時に起こして。", result.text)
        assertEquals("ja", result.languageCode)
    }

    @Test
    fun parsesEnglishOutput() {
        val result = QwenAsrOutputParser.parse(
            "language English<asr_text>Can you hear me?"
        )

        assertEquals("Can you hear me?", result.text)
        assertEquals("en", result.languageCode)
    }

    @Test
    fun parsesPlainTextOutputWithoutMarker() {
        val result = QwenAsrOutputParser.parse("明日の朝七時に起こして。\n")

        assertEquals("明日の朝七時に起こして。", result.text)
        assertEquals(null, result.languageCode)
    }

    @Test
    fun parsesPlainTextOutputForShortUtterance() {
        val result = QwenAsrOutputParser.parse("聞こえますか。")

        assertEquals("聞こえますか。", result.text)
        assertEquals(null, result.languageCode)
    }

    @Test
    fun stripsImEndSuffixFromPlainTextOutput() {
        val result = QwenAsrOutputParser.parse("Can you hear me?<|im_end|>")

        assertEquals("Can you hear me?", result.text)
    }

    @Test
    fun rejectsBlankOutput() {
        try {
            QwenAsrOutputParser.parse("   \n\n  ")
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
