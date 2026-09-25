package com.g150446.voiceharness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HarborCompletionReportTest {
    @Test
    fun `tracker only emits for armed work after the baseline changes and stabilizes`() {
        val tracker = HarborCompletionTracker(stableMs = 2_000L)

        assertNull(tracker.observe("w1", "idle", 500L))
        tracker.arm("w1", "idle", 1_000L)
        assertNull(tracker.observe("w1", "idle", 4_000L))
        assertNull(tracker.observe("w1", "working", 4_100L))
        assertNull(tracker.observe("w1", "working", 6_099L))
        assertNotNull(tracker.observe("w1", "working", 6_100L))
        assertNull(tracker.observe("w1", "working", 8_100L))
    }

    @Test
    fun `not waiting retries and a screen change resets the review allowance`() {
        val tracker = HarborCompletionTracker(
            stableMs = 100L,
            retryMs = 200L,
            maxSameScreenReviews = 2,
        )
        val trackingId = tracker.arm("w1", "idle", 0L)

        assertNull(tracker.observe("w1", "working", 10L))
        assertNotNull(tracker.observe("w1", "working", 110L))
        tracker.onReviewResult(
            "w1",
            trackingId,
            "working",
            HarborCompletionReviewOutcome.NOT_WAITING,
            120L,
        )
        assertNull(tracker.observe("w1", "working", 319L))
        assertNotNull(tracker.observe("w1", "working", 320L))
        tracker.onReviewResult(
            "w1",
            trackingId,
            "working",
            HarborCompletionReviewOutcome.NOT_WAITING,
            330L,
        )
        assertNull(tracker.observe("w1", "working", 1_000L))

        assertNull(tracker.observe("w1", "done", 1_010L))
        assertNotNull(tracker.observe("w1", "done", 1_110L))
    }

    @Test
    fun `reported result disarms exactly once and a new command may report identical output`() {
        val tracker = HarborCompletionTracker(stableMs = 100L)
        val trackingId = tracker.arm("w1", "idle", 0L)
        assertNull(tracker.observe("w1", "done", 10L))
        assertNotNull(tracker.observe("w1", "done", 110L))
        tracker.onReviewResult(
            "w1",
            trackingId,
            "done",
            HarborCompletionReviewOutcome.REPORTED,
            120L,
        )
        assertFalse(tracker.isArmed("w1"))
        assertNull(tracker.observe("w1", "done", 500L))

        tracker.arm("w1", "idle", 1_000L)
        assertNull(tracker.observe("w1", "done", 1_010L))
        assertNotNull(tracker.observe("w1", "done", 1_110L))
    }

    @Test
    fun `tracker keeps workspaces independent and expires abandoned work`() {
        val tracker = HarborCompletionTracker(stableMs = 100L, timeoutMs = 500L)
        tracker.arm("w1", "a", 0L)
        tracker.arm("w2", "a", 50L)

        assertNull(tracker.observe("w1", "b", 100L))
        assertNull(tracker.observe("w2", "b", 100L))
        assertNotNull(tracker.observe("w1", "b", 200L))
        assertNotNull(tracker.observe("w2", "b", 200L))
        assertNull(tracker.observe("w1", "b", 500L))
        assertFalse(tracker.isArmed("w1"))
        assertTrue(tracker.isArmed("w2"))
    }

    @Test
    fun `late review from an older voice instruction cannot finish the newer one`() {
        val tracker = HarborCompletionTracker(stableMs = 100L)
        val oldTrackingId = tracker.arm("w1", "idle", 0L)
        assertNull(tracker.observe("w1", "same", 10L))
        assertNotNull(tracker.observe("w1", "same", 110L))

        tracker.arm("w1", "same", 120L)
        tracker.onReviewResult(
            "w1",
            oldTrackingId,
            "same",
            HarborCompletionReviewOutcome.REPORTED,
            130L,
        )

        assertTrue(tracker.isArmed("w1"))
    }

    @Test
    fun `temporary review failures remain retryable without consuming the screen allowance`() {
        val tracker = HarborCompletionTracker(
            stableMs = 100L,
            failureRetryMs = 200L,
            maxSameScreenReviews = 1,
        )
        val trackingId = tracker.arm("w1", "idle", 0L)
        assertNull(tracker.observe("w1", "done", 10L))
        assertNotNull(tracker.observe("w1", "done", 110L))
        tracker.onReviewResult(
            "w1",
            trackingId,
            "done",
            HarborCompletionReviewOutcome.RETRY,
            120L,
        )

        assertNull(tracker.observe("w1", "done", 319L))
        assertNotNull(tracker.observe("w1", "done", 320L))
    }

    @Test
    fun `review parser accepts fenced json and suppresses not waiting`() {
        val completed = HarborCompletionPrompt.parse(
            """```json
                {"state":"completed","report":"実装とテストが完了しました。変更は3ファイルです。追加文です。"}
                ```""".trimIndent(),
        )
        assertEquals(HarborCompletionState.COMPLETED, completed.state)
        assertEquals("実装とテストが完了しました。変更は3ファイルです。", completed.report)
        assertTrue(completed.shouldSpeak)

        val working = HarborCompletionPrompt.parse(
            """{"state":"not_waiting","report":"まだ処理中です。"}""",
        )
        assertEquals("", working.report)
        assertFalse(working.shouldSpeak)
    }

    @Test
    fun `review report is capped at 160 characters`() {
        val report = HarborCompletionPrompt.limitReport("完了しました。" + "あ".repeat(200))
        assertEquals(HARBOR_COMPLETION_REPORT_MAX_CHARS, report.length)
        assertTrue(report.endsWith("…"))
    }

    @Test
    fun `review prompt includes transcript and screen as evidence`() {
        val prompt = HarborCompletionPrompt.build(
            HarborCompletionCandidate(
                workspaceId = "w1",
                workspaceName = "voice-harness",
                agent = "codex",
                fingerprint = "abc",
                screen = "All tests passed",
                transcript = "assistant: Implemented the feature",
            ),
        )

        assertTrue(prompt.contains("workspace: voice-harness"))
        assertTrue(prompt.contains("agent: codex"))
        assertTrue(prompt.contains("assistant: Implemented the feature"))
        assertTrue(prompt.contains("All tests passed"))
        assertTrue(prompt.contains("最大2文・160字"))
    }
}
