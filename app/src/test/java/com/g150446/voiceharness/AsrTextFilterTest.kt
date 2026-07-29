package com.g150446.voiceharness

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
}
