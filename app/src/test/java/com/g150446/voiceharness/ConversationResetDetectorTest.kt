package com.g150446.voiceharness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationResetDetectorTest {

    @Test
    fun detectsJapaneseResetOnly() {
        val result = ConversationResetDetector.parse("コンテキストをリセットして")
        assertTrue(result.shouldReset)
        assertNull(result.remainingUserText)
    }

    @Test
    fun detectsJapaneseResetWithPunctuation() {
        val result = ConversationResetDetector.parse("会話をリセットして。")
        assertTrue(result.shouldReset)
        assertNull(result.remainingUserText)
    }

    @Test
    fun detectsEnglishResetOnly() {
        val result = ConversationResetDetector.parse("Reset context")
        assertTrue(result.shouldReset)
        assertNull(result.remainingUserText)
    }

    @Test
    fun stripsLeadingResetAndKeepsRemainder() {
        val result = ConversationResetDetector.parse("コンテキストをリセットして。ちいかわについて教えて")
        assertTrue(result.shouldReset)
        assertEquals("ちいかわについて教えて", result.remainingUserText)
    }

    @Test
    fun doesNotResetNormalQuestions() {
        val result = ConversationResetDetector.parse("今日の天気は？")
        assertFalse(result.shouldReset)
        assertNull(result.remainingUserText)
    }

    @Test
    fun doesNotTreatSaishoKaraInsideSentenceAsReset() {
        val embedded = ConversationResetDetector.parse("最初からやり直す方法を教えて")
        assertFalse(embedded.shouldReset)
        val weather = ConversationResetDetector.parse("ハチワレは何色？")
        assertFalse(weather.shouldReset)
    }

    @Test
    fun confirmationPrefersJapanese() {
        val message = ConversationResetDetector.confirmationMessage("ja", null)
        assertTrue(message.contains("クリア"))
    }

    @Test
    fun confirmationPrefersEnglish() {
        val message = ConversationResetDetector.confirmationMessage("en", null)
        assertTrue(message.contains("cleared"))
    }
}
