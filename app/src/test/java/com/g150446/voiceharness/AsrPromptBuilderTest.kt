package com.g150446.voiceharness

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrPromptBuilderTest {

    @Test
    fun japanesePromptPrefersJapaneseAndAllowsEnglishFragments() {
        val prompt = AsrPromptBuilder.build(SpeechBaseLanguage.JAPANESE)
        assertTrue(prompt.contains("primary language of the speech is Japanese"))
        assertTrue(prompt.contains("occasionally insert English words"))
        assertTrue(prompt.contains("do not translate the Japanese parts into English"))
        assertTrue(prompt.contains("empty string"))
        assertTrue(prompt.contains("timestamps"))
        assertFalse(prompt.contains("primary language of the speech is English"))
    }

    @Test
    fun japanesePromptIncludesBuiltInVocabulary() {
        val prompt = AsrPromptBuilder.build(SpeechBaseLanguage.JAPANESE)
        assertTrue(prompt.contains("Preferred spellings"))
        assertTrue(prompt.contains("ちいかわ"))
        assertTrue(prompt.contains("ハチワレ"))
        assertTrue(prompt.contains("うさぎ"))
    }

    @Test
    fun englishPromptPrefersEnglish() {
        val prompt = AsrPromptBuilder.build(SpeechBaseLanguage.ENGLISH)
        assertTrue(prompt.contains("primary language of the speech is English"))
        assertTrue(prompt.contains("Transcribe in English"))
        assertFalse(prompt.contains("primary language of the speech is Japanese"))
        assertFalse(prompt.contains("ちいかわ"))
        assertFalse(prompt.contains("Preferred spellings"))
    }

    @Test
    fun autoPromptAllowsJapaneseOrEnglish() {
        val prompt = AsrPromptBuilder.build(SpeechBaseLanguage.AUTO)
        assertTrue(prompt.contains("either Japanese or English"))
        assertTrue(prompt.contains("Do not translate between Japanese and English"))
        assertTrue(prompt.contains("ちいかわ"))
    }
}
