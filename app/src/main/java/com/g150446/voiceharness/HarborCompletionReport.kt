package com.g150446.voiceharness

import org.json.JSONObject

internal const val HARBOR_COMPLETION_STABLE_MS = 2_000L
internal const val HARBOR_COMPLETION_REPORT_MAX_CHARS = 160
internal const val HARBOR_COMPLETION_RETRY_MS = 10_000L
internal const val HARBOR_COMPLETION_FAILURE_RETRY_MS = 30_000L
internal const val HARBOR_COMPLETION_TIMEOUT_MS = 15 * 60_000L
internal const val HARBOR_COMPLETION_MAX_SAME_SCREEN_REVIEWS = 3

internal data class HarborCompletionCandidate(
    val workspaceId: String,
    val workspaceName: String,
    val agent: String?,
    val fingerprint: String,
    val screen: String,
    val transcript: String,
    val trackingId: Long = 0L,
)

internal enum class HarborCompletionState(val wire: String) {
    NOT_WAITING("not_waiting"),
    COMPLETED("completed"),
    QUESTION("question"),
    PERMISSION("permission"),
    CHOICE("choice");

    companion object {
        fun fromWire(value: String): HarborCompletionState? =
            entries.firstOrNull { it.wire == value.trim().lowercase() }
    }
}

internal data class HarborCompletionReview(
    val state: HarborCompletionState,
    val report: String,
) {
    val shouldSpeak: Boolean get() = state != HarborCompletionState.NOT_WAITING && report.isNotBlank()
}

internal enum class HarborCompletionReviewOutcome {
    NOT_WAITING,
    RETRY,
    REPORTED,
}

/**
 * Tracks only work explicitly armed after a confirmed voice instruction.
 *
 * A `not_waiting` result is deliberately not terminal: coding-agent screens often sit still
 * while the agent is working. The same evidence gets a small number of delayed retries, and any
 * screen change resets that allowance. A reported result disarms the workspace exactly once.
 */
internal class HarborCompletionTracker(
    private val stableMs: Long = HARBOR_COMPLETION_STABLE_MS,
    private val retryMs: Long = HARBOR_COMPLETION_RETRY_MS,
    private val failureRetryMs: Long = HARBOR_COMPLETION_FAILURE_RETRY_MS,
    private val timeoutMs: Long = HARBOR_COMPLETION_TIMEOUT_MS,
    private val maxSameScreenReviews: Int = HARBOR_COMPLETION_MAX_SAME_SCREEN_REVIEWS,
) {
    private data class Entry(
        val trackingId: Long,
        var fingerprint: String?,
        var changedAtMs: Long,
        val armedAtMs: Long,
        var sawChange: Boolean,
        var reviewInFlight: Boolean,
        var nextReviewAtMs: Long,
        var sameScreenReviews: Int,
    )

    private val entries = mutableMapOf<String, Entry>()
    private var nextTrackingId = 1L

    @Synchronized
    fun arm(workspaceId: String, baselineFingerprint: String?, nowMs: Long): Long {
        val trackingId = nextTrackingId++
        entries[workspaceId] = Entry(
            trackingId = trackingId,
            fingerprint = baselineFingerprint,
            changedAtMs = nowMs,
            armedAtMs = nowMs,
            sawChange = false,
            reviewInFlight = false,
            nextReviewAtMs = Long.MAX_VALUE,
            sameScreenReviews = 0,
        )
        return trackingId
    }

    @Synchronized
    fun observe(workspaceId: String, fingerprint: String, nowMs: Long): Long? {
        val current = entries[workspaceId] ?: return null
        if (nowMs - current.armedAtMs >= timeoutMs) {
            entries.remove(workspaceId)
            return null
        }
        if (current.fingerprint != fingerprint) {
            current.fingerprint = fingerprint
            current.changedAtMs = nowMs
            current.sawChange = true
            current.reviewInFlight = false
            current.nextReviewAtMs = nowMs + stableMs
            current.sameScreenReviews = 0
            return null
        }
        if (!current.sawChange || current.reviewInFlight || nowMs < current.nextReviewAtMs) {
            return null
        }
        if (current.sameScreenReviews >= maxSameScreenReviews) return null
        current.reviewInFlight = true
        current.sameScreenReviews++
        return current.trackingId
    }

    @Synchronized
    fun onReviewResult(
        workspaceId: String,
        trackingId: Long,
        fingerprint: String,
        outcome: HarborCompletionReviewOutcome,
        nowMs: Long,
    ) {
        val current = entries[workspaceId] ?: return
        if (current.trackingId != trackingId || current.fingerprint != fingerprint) return
        if (outcome == HarborCompletionReviewOutcome.REPORTED) {
            entries.remove(workspaceId)
            return
        }
        current.reviewInFlight = false
        if (outcome == HarborCompletionReviewOutcome.RETRY) {
            // Transport/parser failures should remain recoverable until the overall timeout;
            // only confirmed `not_waiting` reviews consume the same-screen allowance.
            current.sameScreenReviews = (current.sameScreenReviews - 1).coerceAtLeast(0)
            current.nextReviewAtMs = nowMs + failureRetryMs
        } else {
            current.nextReviewAtMs = nowMs + retryMs
        }
    }

    @Synchronized
    fun cancel(workspaceId: String) {
        entries.remove(workspaceId)
    }

    @Synchronized
    fun isArmed(workspaceId: String): Boolean = entries.containsKey(workspaceId)

    @Synchronized
    fun clear() = entries.clear()
}

internal object HarborCompletionPrompt {
    private const val MAX_SOURCE_CHARS = 12_000

    fun build(candidate: HarborCompletionCandidate): String = buildString {
        appendLine("Terminal HarborのAIコーディングエージェントの最新状態を確認してください。")
        appendLine("同じworkspaceについて以前交わした会話も文脈として使えますが、下の資料を事実の根拠にしてください。")
        appendLine("ユーザーの新しい指示、確認、権限、回答、選択肢を待っている場合だけ報告対象です。")
        appendLine("処理中・思考中（例: 画面下部に「esc to interrupt」、スピナー、Working/Thinking表示がある）ならnot_waitingです。")
        appendLine("エージェントが応答を書き終え、入力欄（❯ や > のプロンプト）で次の指示を待っていて「esc to interrupt」が無いならcompletedです。")
        appendLine("エージェントが起動していない素のシェルプロンプトだけならnot_waitingです。成功・テスト結果・未確認事項を推測しないでください。")
        appendLine("question、permission、choiceでは、ユーザーが答える内容と選択肢をreportに短く含めてください。")
        appendLine("JSONだけを返してください: {\"state\":\"not_waiting|completed|question|permission|choice\",\"report\":\"日本語で最大2文・160字\"}")
        appendLine("workspace: ${candidate.workspaceName}")
        candidate.agent?.takeIf(String::isNotBlank)?.let { appendLine("agent: $it") }
        if (candidate.transcript.isNotBlank()) {
            appendLine("latest_agent_transcript:")
            appendLine(candidate.transcript.takeLast(MAX_SOURCE_CHARS))
        }
        appendLine("current_terminal_screen:")
        append(candidate.screen.takeLast(MAX_SOURCE_CHARS))
    }

    fun parse(text: String): HarborCompletionReview {
        val trimmed = text.trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        require(start >= 0 && end > start) { "完了確認の応答を解析できません" }
        val json = JSONObject(trimmed.substring(start, end + 1))
        val state = HarborCompletionState.fromWire(json.optString("state"))
            ?: error("完了確認の状態が不正です")
        val report = if (state == HarborCompletionState.NOT_WAITING) {
            ""
        } else {
            limitReport(json.optString("report"))
        }
        require(state == HarborCompletionState.NOT_WAITING || report.isNotBlank()) {
            "完了報告が空です"
        }
        return HarborCompletionReview(state, report)
    }

    internal fun limitReport(value: String): String {
        val normalized = value.replace(Regex("\\s+"), " ").trim()
        if (normalized.isEmpty()) return ""
        val sentences = normalized.split(Regex("(?<=[。！？!?])"))
            .filter(String::isNotBlank)
            .take(2)
            .joinToString("")
            .trim()
        if (sentences.length <= HARBOR_COMPLETION_REPORT_MAX_CHARS) return sentences
        return sentences.take(HARBOR_COMPLETION_REPORT_MAX_CHARS - 1).trimEnd() + "…"
    }
}
