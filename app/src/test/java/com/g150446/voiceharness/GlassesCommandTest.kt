package com.g150446.voiceharness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlassesCommandTest {
    @Test
    fun `mode switch requires the glasses phrase at the start`() {
        assertEquals("ハーバーモード", glassesModeSwitchRemainder("グラスモード変更 ハーバーモード"))
        assertEquals("リーダーモード", glassesModeSwitchRemainder("グラスモード変更、リーダーモード"))
        assertEquals("", glassesModeSwitchRemainder("グラスモード変更"))
        assertNull(glassesModeSwitchRemainder("ハーバーモードにして"))
        assertNull(glassesModeSwitchRemainder("5つページ進めて"))
    }

    @Test
    fun `reader page commands parse count and direction`() {
        assertEquals(ReaderPageCommand(5, true), parseReaderPageCommand("5つページ進めて"))
        assertEquals(ReaderPageCommand(3, false), parseReaderPageCommand("3ページ戻して"))
        assertEquals(ReaderPageCommand(1, true), parseReaderPageCommand("次のページ"))
        assertEquals(ReaderPageCommand(1, false), parseReaderPageCommand("前のページ"))
        assertEquals(ReaderPageCommand(5, true), parseReaderPageCommand("五ページ進めて"))
        assertNull(parseReaderPageCommand("この画面を要約して"))
    }

    @Test
    fun `Harbor confirm prompt shows the transcript and tap actions`() {
        val prompt = harborConfirmPrompt("このテストを直して")
        assertEquals(true, prompt.startsWith("確認"))
        assertEquals(true, prompt.contains("このテストを直して"))
        assertEquals(true, prompt.contains("シングルタップで実行"))
        assertEquals(true, prompt.contains("ダブルタップで取り消す"))
    }

    @Test
    fun `Harbor confirm prompt includes AI comment and truncates long STT`() {
        val long = "あ".repeat(HARBOR_CONFIRM_STT_MAX + 10)
        val prompt = harborConfirmPrompt(long, "git push を送りますか？")
        assertEquals(true, prompt.contains("…"))
        assertEquals(true, prompt.contains("AI: git push を送りますか？"))
        assertEquals(false, prompt.contains("あ".repeat(HARBOR_CONFIRM_STT_MAX + 1)))
    }

    @Test
    fun `Harbor confirm clarification omits execute hint`() {
        val prompt = harborConfirmPrompt("曖昧", "何を送りますか？", awaitingClarification = true)
        assertEquals(true, prompt.contains("ダブルタップで言い直す"))
        assertEquals(false, prompt.contains("シングルタップで実行"))
    }

    @Test
    fun `spoken counts cap at twenty`() {
        assertEquals(20, parseReaderPageCommand("30ページ進めて")?.pages)
        assertEquals(20, parseReaderPageCommand("二十ページ進めて")?.pages)
    }
}
