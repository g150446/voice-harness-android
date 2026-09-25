package com.g150446.voiceharness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HarborSubmitPlanTest {
    private val workspaces = listOf(
        HarborWorkspace(id = "ws-a", name = "voice-harness", selected = true, agent = "codex"),
        HarborWorkspace(id = "ws-b", name = "terminal-harbor", selected = false),
    )

    private fun args(
        steps: List<HarborCommandStep> = emptyList(),
        action: HarborCommandAction = HarborCommandAction.INSTRUCTION,
        command: String = "",
        key: String? = null,
        mode: ClaudeCodeMode? = null,
        workspaceId: String? = null,
        workspace: String? = null,
    ) = HarborCommandArgs(
        action = action,
        command = command,
        key = key,
        mode = mode,
        workspace = workspace,
        workspaceId = workspaceId,
        intentSummary = "test",
        steps = steps,
    )

    @Test
    fun `steps run in the order the model planned them`() {
        val plan = planHarborSubmit(
            args(
                steps = listOf(
                    HarborCommandStep(HarborStepAction.INSTRUCTION, command = "/model", submit = false),
                    HarborCommandStep(HarborStepAction.KEY, key = "down"),
                    HarborCommandStep(HarborStepAction.KEY, key = "enter"),
                ),
            ),
            workspaces,
        )

        assertEquals(
            listOf(
                HarborOperation.Instruction("ws-a", "/model", submit = false),
                HarborOperation.Key("ws-a", "down"),
                HarborOperation.Key("ws-a", "enter"),
            ),
            plan.operations,
        )
        assertEquals("/model → ↓ → Enter を送りました", plan.message)
        assertEquals(listOf("ws-a"), harborCompletionWorkspaceIds(plan))
    }

    @Test
    fun `only submitted instructions and enter arm completion reporting`() {
        val draft = HarborSubmitPlan(
            operations = listOf(
                HarborOperation.Instruction("ws-a", "draft", submit = false),
                HarborOperation.Key("ws-a", "down"),
                HarborOperation.SetMode("ws-a", ClaudeCodeMode.PLAN),
                HarborOperation.Activate("ws-b"),
            ),
            message = "",
        )
        val submitted = HarborSubmitPlan(
            operations = listOf(
                HarborOperation.Instruction("ws-a", "run", submit = true),
                HarborOperation.Key("ws-a", "enter"),
            ),
            message = "",
        )

        assertTrue(harborCompletionWorkspaceIds(draft).isEmpty())
        assertEquals(listOf("ws-a"), harborCompletionWorkspaceIds(submitted))
    }

    @Test
    fun `completion agent falls back to the workspace process`() {
        assertEquals(
            "claude",
            HarborWorkspace(
                id = "ws",
                name = "repo",
                selected = true,
                process = "claude",
            ).completionAgent(),
        )
    }

    @Test
    fun `the interpreted workspace wins over whatever is selected now`() {
        val plan = planHarborSubmit(
            args(command = "テスト", workspaceId = "ws-b"),
            workspaces,
        )

        assertEquals(listOf(HarborOperation.Instruction("ws-b", "テスト", submit = true)), plan.operations)
    }

    @Test
    fun `a workspace that has gone away falls back to the selected one`() {
        val plan = planHarborSubmit(
            args(command = "テスト", workspaceId = "ws-gone"),
            workspaces,
        )

        assertEquals("ws-a", plan.operations.single().workspaceId)
    }

    @Test
    fun `a legacy single key command still plans as one key`() {
        val plan = planHarborSubmit(
            args(action = HarborCommandAction.KEY, key = "shift-tab"),
            workspaces,
        )

        assertEquals(listOf(HarborOperation.Key("ws-a", "shift-tab")), plan.operations)
        assertEquals("⇧Tabキーを送りました", plan.message)
    }

    @Test
    fun `a mode change plans one operation that decides its own key presses`() {
        val plan = planHarborSubmit(
            args(action = HarborCommandAction.MODE, mode = ClaudeCodeMode.PLAN),
            workspaces,
        )

        assertEquals(
            listOf(
                HarborOperation.SetMode(
                    "ws-a",
                    ClaudeCodeMode.PLAN,
                    agent = "codex",
                    waitMs = HARBOR_MODE_SETTLE_MS,
                )
            ),
            plan.operations,
        )
        // The line the glass shows comes from the sender, once it has read the screen back.
        assertEquals("", plan.message)
    }

    @Test
    fun `a mode step without a target is refused before anything is sent`() {
        assertThrows(IllegalStateException::class.java) {
            planHarborSubmit(
                args(steps = listOf(HarborCommandStep(HarborStepAction.MODE))),
                workspaces,
            )
        }
    }

    @Test
    fun `an unsendable key stops the whole sequence before anything is sent`() {
        val error = assertThrows(IllegalStateException::class.java) {
            planHarborSubmit(
                args(
                    steps = listOf(
                        HarborCommandStep(HarborStepAction.INSTRUCTION, command = "/model"),
                        HarborCommandStep(HarborStepAction.KEY, key = "f7"),
                    ),
                ),
                workspaces,
            )
        }

        assertTrue(error.message!!.contains("f7"))
    }

    @Test
    fun `an empty instruction is refused`() {
        assertThrows(IllegalStateException::class.java) {
            planHarborSubmit(args(command = "   "), workspaces)
        }
    }

    @Test
    fun `a switch resolves the target by name`() {
        val plan = planHarborSubmit(
            args(action = HarborCommandAction.SWITCH_WORKSPACE, workspace = "terminal-harbor"),
            workspaces,
        )

        assertEquals(listOf(HarborOperation.Activate("ws-b")), plan.operations)
        assertEquals("terminal-harbor に切り替えました", plan.message)
    }

    /** A name that fits two workspaces is there twice over, not missing. */
    @Test
    fun `an ambiguous switch target names the candidates`() {
        val crowded = listOf(
            HarborWorkspace(id = "ws-a", name = "voice-harness-android", selected = true),
            HarborWorkspace(id = "ws-b", name = "voice-harness-even-g2", selected = false),
        )

        val error = assertThrows(IllegalStateException::class.java) {
            planHarborSubmit(
                args(action = HarborCommandAction.SWITCH_WORKSPACE, workspace = "voice-harness"),
                crowded,
            )
        }

        assertTrue(error.message!!.contains("voice-harness-android"))
        assertTrue(error.message!!.contains("voice-harness-even-g2"))
    }

    @Test
    fun `an unknown switch target says so`() {
        val error = assertThrows(IllegalStateException::class.java) {
            planHarborSubmit(
                args(action = HarborCommandAction.SWITCH_WORKSPACE, workspace = "nothing-like-this"),
                workspaces,
            )
        }

        assertTrue(error.message!!.contains("見つかりません"))
    }

    @Test
    fun `a per-step wait is carried through`() {
        val plan = planHarborSubmit(
            args(
                steps = listOf(
                    HarborCommandStep(HarborStepAction.INSTRUCTION, command = "/model", submit = false, waitMs = 1_200),
                    HarborCommandStep(HarborStepAction.KEY, key = "enter"),
                ),
            ),
            workspaces,
        )

        assertEquals(1_200, plan.operations.first().waitMs)
        assertEquals(HARBOR_STEP_DELAY_MS, plan.operations.last().waitMs)
    }
}
