package com.g150446.voiceharness

import com.g150446.voiceharness.UtteranceIntentGate.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cases are taken verbatim from the on-device history of 2026-08-27..08-31
 * (100 entries, 54 non-silent). The four accidental triggers recorded during the
 * 10:00–12:00 clinic block are the regression anchors.
 */
class UtteranceIntentGateTest {

    private fun evaluate(text: String, active: Boolean = false) =
        UtteranceIntentGate.evaluate(text, SpeechBaseLanguage.JAPANESE, active)

    // --- The four confirmed accidental triggers ---

    @Test
    fun `clinic false positives are suppressed`() {
        assertEquals(Verdict.SUPPRESS_BACKCHANNEL, evaluate("はい、はい。"))
        assertEquals(Verdict.SUPPRESS_BACKCHANNEL, evaluate("はい、お疲れ様です。どうも。"))
        assertEquals(Verdict.SUPPRESS_FRAGMENT, evaluate("老廃物を"))
    }

    @Test
    fun `whisper silence hallucinations are suppressed under japanese`() {
        listOf(
            "I'm going to go.",
            "I'm going to go ahead and put it in the middle.",
            "All right.",
            "Thank you. Thank you. Thank you.",
            "I'm sorry.",
            "PHONE RINGS",
            "BEEP BEEP BEEP",
        ).forEach { assertEquals(it, Verdict.SUPPRESS_NON_CJK, evaluate(it)) }
    }

    @Test
    fun `short japanese cut off at a particle is suppressed`() {
        assertEquals(Verdict.SUPPRESS_FRAGMENT, evaluate("もう本当に"))
    }

    // --- Real commands must keep working ---

    @Test
    fun `intentional commands pass`() {
        listOf(
            "ホーム画面からパススルーモード",
            "パススルーモードに入って",
            "リンゴと一言言って",
            "みかんと一言答えてる",
            "玉ねぎと一言だけ答えて",
            "明日17時にリマインダーをセット排水口の溶かすや使う",
            "この画面の内容について説明して",
            "機械学習について簡単に説明して",
            "あなたが人生において最も大切だと思うことを教えて",
            "チーカーというアニメの登場人物について教えて",
            "このように表示されるとき、9/3に到着する可能性はどれくらい？",
        ).forEach { assertEquals(it, Verdict.PASS, evaluate(it)) }
    }

    @Test
    fun `conversation reset commands pass`() {
        assertEquals(Verdict.PASS, evaluate("コンテキストをリセット"))
        assertEquals(Verdict.PASS, evaluate("これまでの会話を忘れて"))
    }

    // --- Escape hatches ---

    @Test
    fun `bare reply passes while a session is open`() {
        assertEquals(Verdict.SUPPRESS_BACKCHANNEL, evaluate("はい", active = false))
        assertEquals(Verdict.PASS, evaluate("はい", active = true))
    }

    @Test
    fun `latin utterances pass when japanese is not the base language`() {
        assertEquals(
            Verdict.PASS,
            UtteranceIntentGate.evaluate("All right.", SpeechBaseLanguage.ENGLISH, false),
        )
        assertEquals(
            Verdict.PASS,
            UtteranceIntentGate.evaluate("All right.", SpeechBaseLanguage.AUTO, false),
        )
    }

    @Test
    fun `politeness is not mistaken for a question`() {
        // 「です」 must not match the 「ですか」 question form.
        assertEquals(Verdict.SUPPRESS_BACKCHANNEL, evaluate("お疲れ様です"))
    }

    @Test
    fun `a long particle-final clause is left alone`() {
        // Beyond FRAGMENT_MAX_CJK: too much content to call it a cut-off fragment.
        assertEquals(Verdict.PASS, evaluate("先週提出した書類の控えと請求書は"))
    }

    @Test
    fun `empty text is left to AsrTextFilter`() {
        assertEquals(Verdict.PASS, evaluate("   "))
    }

    // --- Helper ---

    @Test
    fun `backchannel matching consumes repeats without punctuation`() {
        assertTrue(UtteranceIntentGate.isBackchannelOnly("はいはい"))
        assertTrue(UtteranceIntentGate.isBackchannelOnly("はい、どうも。"))
        assertFalse(UtteranceIntentGate.isBackchannelOnly("はい、老廃物を"))
    }
}
