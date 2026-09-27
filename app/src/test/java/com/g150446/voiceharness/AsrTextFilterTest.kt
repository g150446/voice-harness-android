package com.g150446.voiceharness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrTextFilterTest {

    @Test
    fun rejectsEmptyAndKnownHallucinations() {
        assertTrue(AsrTextFilter.isGarbageOrEmpty(""))
        assertTrue(AsrTextFilter.isGarbageOrEmpty("   "))
        assertTrue(AsrTextFilter.isGarbageOrEmpty("Thank you"))
        assertTrue(AsrTextFilter.isGarbageOrEmpty("you"))
        assertTrue(AsrTextFilter.isGarbageOrEmpty("bye."))
    }

    @Test
    fun rejectsDigitNoiseFromBackgroundMusic() {
        assertTrue(AsrTextFilter.isGarbageOrEmpty("3"))
        assertTrue(AsrTextFilter.isGarbageOrEmpty("23"))
        assertTrue(AsrTextFilter.isGarbageOrEmpty("1.7"))
        assertTrue(AsrTextFilter.isGarbageOrEmpty("..."))
    }

    @Test
    fun keepsShortJapaneseQuietSpeech() {
        assertFalse(AsrTextFilter.isGarbageOrEmpty("はい"))
        assertFalse(AsrTextFilter.isGarbageOrEmpty("うん"))
        assertFalse(AsrTextFilter.isGarbageOrEmpty("ありがとう"))
        assertFalse(AsrTextFilter.isGarbageOrEmpty("今何時？"))
    }

    @Test
    fun keepsRealEnglishPhrases() {
        assertFalse(AsrTextFilter.isGarbageOrEmpty("hello"))
        assertFalse(AsrTextFilter.isGarbageOrEmpty("what time is it"))
    }

    @Test
    fun rejectsVeryShortLatinNoise() {
        assertTrue(AsrTextFilter.isGarbageOrEmpty("a"))
        assertTrue(AsrTextFilter.isGarbageOrEmpty("ok"))
        assertTrue(AsrTextFilter.isGarbageOrEmpty("hi"))
    }

    @Test
    fun rejectsChiikawaDumpWithoutAnime() {
        assertTrue(AsrTextFilter.isGarbageOrEmpty("ちいかわ、ハチワレ、うさぎ"))
        assertTrue(AsrTextFilter.isGarbageOrEmpty("ちいかわ ハチワレ"))
        assertTrue(AsrTextFilter.isVocabularyEchoWithoutTrigger("ちいかわ、ハチワレ、うさぎ"))
    }

    @Test
    fun keepsChiikawaWhenAnimeIsPresentOrSpeechHasMoreWords() {
        assertFalse(AsrTextFilter.isGarbageOrEmpty("アニメのちいかわについて教えて"))
        assertFalse(AsrTextFilter.isGarbageOrEmpty("ちいかわ"))
        assertFalse(AsrTextFilter.isGarbageOrEmpty("ちいかわについて教えて"))
        assertFalse(AsrTextFilter.isGarbageOrEmpty("ハチワレは何色？"))
        assertFalse(AsrTextFilter.isVocabularyEchoWithoutTrigger("アニメのちいかわ、ハチワレ、うさぎ"))
    }

    @Test
    fun rejectsJapaneseCourtesyDumpFromNoise() {
        assertTrue(AsrTextFilter.isGarbageOrEmpty("はい、ありがとうございます"))
        assertTrue(AsrTextFilter.isGarbageOrEmpty("はい。ありがとうございます。"))
        assertTrue(AsrTextFilter.isGarbageOrEmpty("ありがとうございます"))
        assertTrue(AsrTextFilter.isGarbageOrEmpty("ありがとうございました"))
        assertTrue(AsrTextFilter.isPolitenessHallucination("はい、ありがとうございます"))
    }

    @Test
    fun keepsRealThanksAndYesWithoutCourtesyDump() {
        assertFalse(AsrTextFilter.isGarbageOrEmpty("はい"))
        assertFalse(AsrTextFilter.isGarbageOrEmpty("うん"))
        assertFalse(AsrTextFilter.isGarbageOrEmpty("ありがとう"))
        assertFalse(AsrTextFilter.isGarbageOrEmpty("はい、今何時？"))
    }

    @Test
    fun `an unfinished vocabulary term after the speech is cut`() {
        val vocab = listOf("グラスモード変更", "パイロットモード", "Pilotモード", "Chrome")
            .map { AsrVocabularyTerm(it) }
        // Seen on the device with the Whisper prompt.
        assertEquals(
            "Chromeアプリを開いて",
            AsrTextFilter.stripTrailingVocabularyFragment("Chromeアプリを開いてP", vocab),
        )
        assertEquals(
            "表示されました",
            AsrTextFilter.stripTrailingVocabularyFragment("表示されましたパイロットモ", vocab),
        )
        assertEquals(
            "開いて。",
            AsrTextFilter.stripTrailingVocabularyFragment("開いて。Pilot", vocab),
        )
        // Complete terms and ordinary endings stay.
        assertEquals(
            "パイロットモード",
            AsrTextFilter.stripTrailingVocabularyFragment("パイロットモード", vocab),
        )
        assertEquals(
            "ハーバーからパイロットモード",
            AsrTextFilter.stripTrailingVocabularyFragment("ハーバーからパイロットモード", vocab),
        )
        assertEquals(
            "Chromeを開いて",
            AsrTextFilter.stripTrailingVocabularyFragment("Chromeを開いて", vocab),
        )
    }
}
