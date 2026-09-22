package com.g150446.voiceharness

import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HarborPlanTest {
    private fun sha256Of(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun availableJson(text: String, sha: String = sha256Of(text), complete: Boolean = true) =
        JSONObject()
            .put("workspace_id", "ws-1")
            .put("agent", "Claude")
            .put("capability", "agent_file")
            .put("available", true)
            .put("source", "claude_plan_file")
            .put("text", text)
            .put("updated_at", "2026-09-22T12:34:56Z")
            .put("content_sha256", sha)
            .put("complete", complete)

    @Test
    fun `a whole plan is returned verbatim`() {
        val text = "# Plan\n\n1. まず調べる\n2. 次に直す\n"

        val plan = parseHarborPlan(200, availableJson(text))

        assertTrue(plan.available)
        assertEquals(text, plan.text)
        assertEquals("Claude", plan.agent)
        assertEquals("agent_file", plan.capability)
        assertEquals("2026-09-22T12:34:56Z", plan.updatedAt)
    }

    @Test(expected = IllegalStateException::class)
    fun `a hash mismatch is refused rather than shown`() {
        parseHarborPlan(200, availableJson("# Plan\n", sha = sha256Of("something else")))
    }

    @Test(expected = IllegalStateException::class)
    fun `an incomplete success is refused`() {
        parseHarborPlan(200, availableJson("# Plan\n", complete = false))
    }

    @Test
    fun `no plan yet is a normal answer with a reason`() {
        val json = JSONObject()
            .put("workspace_id", "ws-1")
            .put("agent", "Claude")
            .put("capability", "agent_file")
            .put("available", false)
            .put("reason", "plan_not_created")

        val plan = parseHarborPlan(200, json)

        assertFalse(plan.available)
        assertEquals("plan_not_created", plan.reason)
        assertEquals("", plan.text)
        assertEquals("このセッションはまだプランを作成していません。", harborPlanReasonText(plan))
    }

    @Test
    fun `an oversized plan reports its own reason`() {
        val json = JSONObject()
            .put("workspace_id", "ws-1")
            .put("capability", "agent_file")
            .put("available", false)
            .put("reason", "plan_too_large")

        val plan = parseHarborPlan(413, json)

        assertFalse(plan.available)
        assertEquals("plan_too_large", plan.reason)
        assertTrue(harborPlanReasonText(plan).contains("1MiB"))
    }

    @Test
    fun `missing hooks tells the user how to install them`() {
        val plan = HarborPlan(available = false, capability = "agent_file", reason = "session_unidentified")

        assertTrue(harborPlanReasonText(plan).contains("agent-session install-hooks"))
    }

    @Test
    fun `an agent without plan files is named in the message`() {
        val plan = HarborPlan(
            available = false,
            capability = "none",
            agent = "Codex",
            reason = "agent_has_no_plan_file",
        )

        assertEquals("このエージェント（Codex）はプランファイルを持ちません。", harborPlanReasonText(plan))
    }

    @Test
    fun `an older bridge is reported as unsupported, not as an empty plan`() {
        val plan = HarborPlan(available = false, capability = "unknown", reason = "unsupported_api")

        assertTrue(harborPlanReasonText(plan).contains("API 1.11.0"))
    }

    @Test
    fun `an unknown reason still produces a message`() {
        val plan = HarborPlan(available = false, capability = "unknown", reason = "something_new")

        assertEquals("プランを取得できませんでした。", harborPlanReasonText(plan))
    }
}
