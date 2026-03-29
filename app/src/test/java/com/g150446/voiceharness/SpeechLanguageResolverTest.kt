package com.g150446.voiceharness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class SpeechLanguageResolverTest {

    @Test
    fun resolvePreferredLanguageCode_prefersWhisperLanguage() {
        val languageCode = SpeechLanguageResolver.resolvePreferredLanguageCode("ja", "hello world")

        assertEquals("ja", languageCode)
    }

    @Test
    fun resolvePreferredLanguageCode_detectsJapaneseFromKana() {
        val languageCode = SpeechLanguageResolver.resolvePreferredLanguageCode(null, "こんにちは、元気ですか")

        assertEquals("ja", languageCode)
    }

    @Test
    fun resolvePreferredLanguageCode_detectsEnglishFromLatinScript() {
        val languageCode = SpeechLanguageResolver.resolvePreferredLanguageCode(null, "Hello, how are you?")

        assertEquals("en", languageCode)
    }

    @Test
    fun resolvePreferredLanguageCode_returnsNullForAmbiguousText() {
        val languageCode = SpeechLanguageResolver.resolvePreferredLanguageCode(null, "12345 !?")

        assertNull(languageCode)
    }

    @Test
    fun candidateLocales_expandsRegionAndKeepsFallback() {
        val locales = SpeechLanguageResolver.candidateLocales("en-US", Locale.JAPANESE)

        assertEquals(listOf("en-US", "en", "ja"), locales.map { it.toLanguageTag() })
    }

    @Test
    fun candidateLocales_deduplicatesFallbackLocale() {
        val locales = SpeechLanguageResolver.candidateLocales("en", Locale.ENGLISH)

        assertEquals(listOf("en"), locales.map { it.toLanguageTag() })
    }
}
