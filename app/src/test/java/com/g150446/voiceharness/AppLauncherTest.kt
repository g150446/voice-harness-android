package com.g150446.voiceharness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLauncherTest {
    private val chrome = LaunchableApp("Chrome", "com.android.chrome")
    private val grok = LaunchableApp("Grok", "ai.x.grok")
    private val settings = LaunchableApp("設定", "com.android.settings")
    private val youtube = LaunchableApp("YouTube", "com.google.android.youtube")
    private val self = LaunchableApp("Voice Harness", "com.g150446.voiceharness")
    private val apps = listOf(chrome, grok, settings, youtube, self)

    private fun resolve(utterance: String, toLatin: (String) -> String = { it }): LaunchableApp? =
        spokenAppLaunchTarget(utterance)?.let { matchLaunchableApp(it, apps, toLatin) }

    @Test
    fun `only a bare open request names an app`() {
        assertEquals("クロムブラウザ", spokenAppLaunchTarget("クロムブラウザアプリを開いて"))
        assertEquals("Chrome", spokenAppLaunchTarget("Chromeを開いて。"))
        assertEquals("Glock", spokenAppLaunchTarget("Glock を起動して"))
        assertEquals("設定", spokenAppLaunchTarget("設定アプリを立ち上げてください"))
        // Seen on the device: ASR returned the command twice.
        assertEquals(
            "クロムブラウザ",
            spokenAppLaunchTarget("クロムブラウザアプリを開いてクロムブラウザアプリを開いて"),
        )
        assertEquals(
            InteractionMode.AI,
            spokenInteractionModeSwitch("パイロットモードに切り替えてパイロットモードに切り替えて"),
        )
        assertEquals("Chrome", spokenAppLaunchTarget("Chromeアプリを開いてP"))
        assertEquals("Chrome", spokenAppLaunchTarget("Chromeを開いてパッ"))
        assertNull(spokenAppLaunchTarget("Chromeの使い方を教えて"))
        assertNull(spokenAppLaunchTarget("明日の天気は"))
    }

    @Test
    fun `ASR spellings resolve to the installed app`() {
        assertEquals(chrome, resolve("クロムブラウザアプリを開いて"))
        assertEquals(chrome, resolve("クロームを開いて"))
        assertEquals(chrome, resolve("Chromeブラウザを開いて"))
        assertEquals(grok, resolve("Glockを起動して"))
        assertEquals(grok, resolve("グロックを開いて"))
        assertEquals(settings, resolve("設定を開いて"))
        assertEquals(youtube, resolve("ユーチューブを開いて"))
        assertEquals(self, resolve("ボイスハーネスアプリを開いて"))
        assertEquals(self, resolve("Voice Harnessを開いて"))
        // Seen on the device: the long vowel mark is doubled.
        assertEquals(self, resolve("ボイスハーネースアプリを開いて"))
    }

    @Test
    fun `STT hints put this app and known apps first`() {
        val kindle = LaunchableApp("Kindle", "com.amazon.kindle")
        val hints = appSpeechHints(listOf(kindle, settings, chrome, self), self.packageName)
        assertEquals(listOf("Voice Harness", "Chrome", "Kindle", "設定"), hints)
    }

    @Test
    fun `Whisper prompt joins whole terms within its budget`() {
        val terms = listOf("パイロットモード", "Chrome", "Chrome", "Voice Harness")
            .map { AsrVocabularyTerm(it) }
        assertEquals(
            "パイロットモード、Chrome、Voice Harness",
            AsrVocabularyCatalog.whisperPrompt(terms),
        )
        val long = (1..100).map { AsrVocabularyTerm("アプリ名$it") }
        val prompt = AsrVocabularyCatalog.whisperPrompt(long)!!
        assert(prompt.length <= AsrVocabularyCatalog.MAX_WHISPER_PROMPT_CHARS)
        assert(prompt.split("、").all { it.startsWith("アプリ名") })
        assertNull(AsrVocabularyCatalog.whisperPrompt(emptyList()))
    }

    @Test
    fun `romanized katakana near-matches a Latin label`() {
        val slack = LaunchableApp("Slack", "com.Slack")
        val discord = LaunchableApp("Discord", "com.discord")
        val installed = listOf(slack, discord, chrome)
        // What ICU's Katakana-Latin transliterator produces on the device, given the
        // normalized name (long vowel mark already dropped).
        val romaji = mapOf("スラック" to "surakku", "ディスコド" to "disukodo")
        val toLatin: (String) -> String = { romaji[it] ?: it }
        assertEquals(slack, matchLaunchableApp("スラック", installed, toLatin))
        assertEquals(discord, matchLaunchableApp("ディスコード", installed, toLatin))
        assertEquals("krm", consonantSkeleton("kuromu"))
        assertEquals("krm", consonantSkeleton("Chrome"))
    }

    @Test
    fun `unknown or uninstalled apps are not found`() {
        assertNull(resolve("窓を開いて"))
        // The LINE alias exists, but LINE is not installed.
        assertNull(resolve("ラインを開いて"))
    }
}
