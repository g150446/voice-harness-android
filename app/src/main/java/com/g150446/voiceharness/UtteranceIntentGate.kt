package com.g150446.voiceharness

/**
 * Decides whether a transcript is actually addressed to the assistant.
 *
 * The recording gesture (palm-up still → lift → pronation → 500 ms hold) is
 * physically identical to everyday arm motion — checking a wristwatch, using a
 * stethoscope — and FW diagnostics from 2026-08-31 showed accidental triggers
 * sitting fully inside the true-positive distribution of every motion gate
 * (match impulse, pronation angle, lift impulse, xy ratio, hold length).
 * No firmware threshold can separate them, so the utterance itself is the last
 * place to catch one before it reaches the LLM and the speaker.
 *
 * Unknown Japanese phrasing still passes, while Latin-only text must positively
 * match an English request/question form. Field history showed that otherwise
 * permissive language detection lets silence hallucinations and Chinese through.
 *
 * [AsrTextFilter] runs first and drops clear garbage; this handles utterances
 * that are real speech but were never meant for the assistant.
 */
object UtteranceIntentGate {

    enum class Verdict {
        PASS,

        /** A script outside Japanese and English, including han-only Chinese. */
        SUPPRESS_FOREIGN,

        /** Latin text that is not shaped like an English request or question. */
        SUPPRESS_NON_REQUEST,

        /** Nothing but conversational filler ("はい、はい。"). */
        SUPPRESS_BACKCHANNEL,

        /** Short clause cut off at a case particle ("老廃物を"). */
        SUPPRESS_FRAGMENT,
        ;

        val isSuppressed: Boolean get() = this != PASS
    }

    private val kana = Regex("[\\u3040-\\u30ff]")
    private val han = Regex("[\\u3400-\\u4dbf\\u4e00-\\u9fff]")
    private val japanese = Regex("[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff]")
    private val foreignScript = Regex(
        "[\\u0370-\\u03ff\\u0400-\\u052f\\u0590-\\u05ff\\u0600-\\u06ff" +
            "\\u0900-\\u097f\\u0e00-\\u0e7f\\u1100-\\u11ff\\u3130-\\u318f" +
            "\\uac00-\\ud7af]"
    )
    private val latinLetter = Regex("[A-Za-z]")
    private val englishRequest = Regex(
        "^(tell|set|show|give|find|search|play|remind|explain|write|read|open|close|" +
            "stop|start|make|create|send|help|list|add|turn|call|check|translate|" +
            "summari[sz]e|describe|what|who|when|where|why|how|which|can|could|" +
            "would|will|should|is|are|do|does|did|please)\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Request or question forms. A hit means PASS unconditionally, so these stay
     * narrow enough not to match politeness: 「ですか」 is here, 「です」 is not.
     */
    private val requestForm = Regex(
        "(して|ください|下さい|ちょうだい|教えて|言って|答えて|つぶやいて|" +
            "セット|やって|調べて|作って|送って|読んで|見せて|開いて|止めて|入って)"
    )
    private val questionForm = Regex(
        "(\\?|？|ですか|でしょうか|だっけ|どれくらい|どのくらい|なぜ|どうやって|何ですか)"
    )

    /** Phrases that drive app features; the LLM resolves them into tool calls. */
    private val appCommand = Regex("(パススルー|ホーム画面|リマインダー|コンテキスト)")

    /**
     * Filler that carries no request. Matched greedily against the whole
     * utterance so 「はいはい」 is caught alongside 「はい、はい。」.
     */
    private val backchannelTerms = listOf(
        "ありがとうございました", "ありがとうございます", "お疲れさまでした", "お疲れ様でした",
        "お疲れさまです", "お疲れ様です", "わかりました", "分かりました", "そうですね",
        "ありがとう", "すみません", "なるほど", "お疲れ様", "了解", "どうも",
        "はい", "ええ", "うん", "そう",
    ).sortedByDescending { it.length }

    private val separators = Regex("[\\s\\u3000、。,\\.!！?？・…]+")

    /** Case particles. A short clause ending here lost its predicate. */
    private val particleEnd = Regex("[をがはにへとでもの][。\\.、,！!]*$")

    /** Above this, a particle-final clause is long enough to be a real query. */
    private const val FRAGMENT_MAX_CJK = 10

    /**
     * @param conversationActive whether a multi-turn session is still open, which
     *   makes a bare 「はい」 a legitimate answer to the assistant's own question.
     *   Suppressed utterances never reach the LLM and so never open a session —
     *   one accidental trigger cannot waive the next one's backchannel check.
     */
    fun evaluate(
        text: String,
        baseLanguage: SpeechBaseLanguage,
        conversationActive: Boolean,
    ): Verdict {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Verdict.PASS // AsrTextFilter already handles empty.

        if (appCommand.containsMatchIn(trimmed)) return Verdict.PASS
        if (requestForm.containsMatchIn(trimmed)) return Verdict.PASS
        if (questionForm.containsMatchIn(trimmed)) return Verdict.PASS
        if (ConversationResetDetector.parse(trimmed).shouldReset) return Verdict.PASS

        val hasKana = kana.containsMatchIn(trimmed)
        val hasHan = han.containsMatchIn(trimmed)
        if (!hasKana && (hasHan || foreignScript.containsMatchIn(trimmed))) {
            return Verdict.SUPPRESS_FOREIGN
        }
        if (!hasKana && !hasHan && latinLetter.containsMatchIn(trimmed)) {
            return if (englishRequest.containsMatchIn(trimmed) || '?' in trimmed) {
                Verdict.PASS
            } else {
                Verdict.SUPPRESS_NON_REQUEST
            }
        }

        if (!conversationActive && isBackchannelOnly(trimmed)) {
            return Verdict.SUPPRESS_BACKCHANNEL
        }

        val cjkCount = trimmed.count { japanese.matches(it.toString()) }
        if (cjkCount <= FRAGMENT_MAX_CJK && particleEnd.containsMatchIn(trimmed)) {
            return Verdict.SUPPRESS_FRAGMENT
        }

        return Verdict.PASS
    }

    /**
     * True when the whole utterance is consumed by backchannel terms.
     * Same greedy longest-first walk as [AsrTextFilter.isVocabularyOnlyDump].
     */
    internal fun isBackchannelOnly(text: String): Boolean {
        var rest = text.replace(separators, "")
        if (rest.isEmpty()) return false
        while (rest.isNotEmpty()) {
            val hit = backchannelTerms.firstOrNull { rest.startsWith(it) } ?: return false
            rest = rest.substring(hit.length)
        }
        return true
    }
}
