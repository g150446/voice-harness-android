package com.g150446.voiceharness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeCodeModeTest {
    private fun screen(footer: String) = """
        ● Read HarborIntegration.kt (1519 lines)

        ╭──────────────────────────────────────────────────────╮
        │ >                                                    │
        ╰──────────────────────────────────────────────────────╯
        $footer
    """.trimIndent()

    @Test
    fun `each footer names its mode`() {
        assertEquals(
            ClaudeCodeMode.PLAN,
            readClaudeCodeMode(screen("  ⏸ plan mode on (shift+tab to cycle)")),
        )
        assertEquals(
            ClaudeCodeMode.ACCEPT_EDITS,
            readClaudeCodeMode(screen("  ⏵⏵ accept edits on (shift+tab to cycle)")),
        )
        assertEquals(
            ClaudeCodeMode.AUTO,
            readClaudeCodeMode(screen("  ⏵⏵ auto mode on")),
        )
        assertEquals(
            ClaudeCodeMode.DONT_ASK,
            readClaudeCodeMode(screen("  ⏵⏵ don’t ask on")),
        )
        assertEquals(
            ClaudeCodeMode.BYPASS_PERMISSIONS,
            readClaudeCodeMode(screen("  ⏵⏵ bypass permissions on (shift+tab to cycle)")),
        )
    }

    @Test
    fun `a footer naming no mode is normal mode`() {
        assertEquals(ClaudeCodeMode.NORMAL, readClaudeCodeMode(screen("  ? for shortcuts")))
    }

    @Test
    fun `a screen without the input box says nothing`() {
        val busy = """
            ● Running tests…
              ⎿  BUILD SUCCESSFUL in 41s

            ✻ Compiling… (12s · ↓ 1.2k tokens · esc to interrupt)
        """.trimIndent()

        assertNull(readClaudeCodeMode(busy))
    }

    @Test
    fun `the footer wins over the agent talking about plan mode`() {
        val chatty = """
            ● プランモードのままコミットはできません。plan mode on のときは書き込みが止まります。

            ╭──────────────────────────────────────────────────────╮
            │ >                                                    │
            ╰──────────────────────────────────────────────────────╯
              ? for shortcuts
        """.trimIndent()

        assertEquals(ClaudeCodeMode.NORMAL, readClaudeCodeMode(chatty))
    }

    @Test
    fun `a mode line scrolled out of the footer is not read as the mode`() {
        val stale = buildString {
            appendLine("  ⏸ plan mode on (shift+tab to cycle)")
            repeat(20) { appendLine("● 行 $it") }
        }

        assertNull(readClaudeCodeMode(stale))
    }

    @Test
    fun `Codex default screen is not mistaken for unreadable Claude Code`() {
        val codex = """
            • Ready for the next task.

            › Ask Codex to do anything

              GPT-5.6-Sol medium · ~/projects/voice-harness-even-g2
        """.trimIndent()

        assertEquals(ClaudeCodeMode.NORMAL, readCodexMode(codex))
        assertTrue(isCodexAgent("Codex"))
    }

    @Test
    fun `Codex plan footer names plan mode`() {
        val codex = """
            › Ask Codex to do anything

              GPT-5.6-Sol medium · ~/projects/voice-harness-… Plan mode    ⚠ 1 warning · f2 to view
        """.trimIndent()

        assertEquals(ClaudeCodeMode.PLAN, readCodexMode(codex))
    }

    @Test
    fun `Codex talking about plan mode is not mistaken for its footer`() {
        val codex = """
            • Plan mode is useful before implementation.

            › Ask Codex to do anything
        """.trimIndent()

        assertEquals(ClaudeCodeMode.NORMAL, readCodexMode(codex))
    }

    @Test
    fun `Claude plan footer is not mistaken for Codex`() {
        assertNull(readCodexMode(screen("  ⏸ plan mode on (shift+tab to cycle)")))
    }

    @Test
    fun `Codex screen without its composer says nothing`() {
        assertNull(readCodexMode("• Running tests…\n  Working (12s)"))
    }

    @Test
    fun `wire names and spoken aliases both resolve`() {
        assertEquals(ClaudeCodeMode.PLAN, ClaudeCodeMode.fromWire("plan"))
        assertEquals(ClaudeCodeMode.ACCEPT_EDITS, ClaudeCodeMode.fromWire("accept_edits"))
        assertEquals(ClaudeCodeMode.ACCEPT_EDITS, ClaudeCodeMode.fromWire("accept edits"))
        assertEquals(ClaudeCodeMode.BYPASS_PERMISSIONS, ClaudeCodeMode.fromWire("bypass"))
        assertEquals(ClaudeCodeMode.AUTO, ClaudeCodeMode.fromWire("auto"))
        assertEquals(ClaudeCodeMode.DONT_ASK, ClaudeCodeMode.fromWire("dont_ask"))
        assertEquals(ClaudeCodeMode.NORMAL, ClaudeCodeMode.fromWire("通常"))
        assertEquals(ClaudeCodeMode.NORMAL, ClaudeCodeMode.fromWire("manual"))
        assertNull(ClaudeCodeMode.fromWire("opus"))
        assertNull(ClaudeCodeMode.fromWire(""))
    }

    // --- cycling ---

    private val cycle = listOf(
        ClaudeCodeMode.NORMAL,
        ClaudeCodeMode.ACCEPT_EDITS,
        ClaudeCodeMode.PLAN,
    )

    @Test
    fun `it presses until the screen shows the mode asked for`() {
        val cycler = FakeCycler(cycle, start = ClaudeCodeMode.NORMAL)

        val result = cycleToClaudeMode(ClaudeCodeMode.PLAN, cycler)

        assertEquals(ClaudeModeCycleResult(ClaudeCodeMode.PLAN, 2), result)
        assertEquals(2, cycler.presses)
    }

    @Test
    fun `already being in the mode sends no key at all`() {
        val cycler = FakeCycler(cycle, start = ClaudeCodeMode.PLAN)

        val result = cycleToClaudeMode(ClaudeCodeMode.PLAN, cycler)

        assertEquals(0, result.presses)
        assertEquals(0, cycler.presses)
    }

    @Test
    fun `an unreadable screen stops it before the first press`() {
        val cycler = FakeCycler(cycle, start = ClaudeCodeMode.NORMAL, unreadableFrom = 0)

        val error = assertThrows(IllegalStateException::class.java) {
            cycleToClaudeMode(ClaudeCodeMode.PLAN, cycler)
        }

        assertEquals(0, cycler.presses)
        assertTrue(error.message!!.contains("判定できません"))
    }

    @Test
    fun `losing the screen mid-cycle says how far it got`() {
        val cycler = FakeCycler(cycle, start = ClaudeCodeMode.NORMAL, unreadableFrom = 1)

        val error = assertThrows(IllegalStateException::class.java) {
            cycleToClaudeMode(ClaudeCodeMode.PLAN, cycler)
        }

        assertEquals(1, cycler.presses)
        assertTrue(error.message!!.contains("1回"))
    }

    @Test
    fun `a redraw that has not landed yet is read again rather than pressed past`() {
        val cycler = FakeCycler(cycle, start = ClaudeCodeMode.NORMAL, flakyReads = 1)

        val result = cycleToClaudeMode(ClaudeCodeMode.ACCEPT_EDITS, cycler)

        assertEquals(1, result.presses)
        assertEquals(2, cycler.settles)
    }

    @Test
    fun `a mode this session does not have stops after one lap`() {
        val cycler = FakeCycler(cycle, start = ClaudeCodeMode.NORMAL)

        val error = assertThrows(IllegalStateException::class.java) {
            cycleToClaudeMode(ClaudeCodeMode.BYPASS_PERMISSIONS, cycler)
        }

        // Round the cycle once and no further: three presses is back where it started.
        assertEquals(3, cycler.presses)
        assertTrue(error.message!!.contains("権限スキップ"))
        // The user is left standing in whatever mode it stopped in, so it has to be named.
        assertTrue(error.message!!.contains("通常"))
    }

    @Test
    fun `Codex presses shift tab once to reach plan and verifies the result`() {
        val toggler = FakeCodexToggler(ClaudeCodeMode.NORMAL)

        val result = cycleToCodexMode(ClaudeCodeMode.PLAN, toggler)

        assertEquals(CodexModeCycleResult(ClaudeCodeMode.PLAN, 1), result)
        assertEquals(1, toggler.presses)
    }

    @Test
    fun `Codex already in plan does not toggle back to default`() {
        val toggler = FakeCodexToggler(ClaudeCodeMode.PLAN)

        val result = cycleToCodexMode(ClaudeCodeMode.PLAN, toggler)

        assertEquals(CodexModeCycleResult(ClaudeCodeMode.PLAN, 0), result)
        assertEquals(0, toggler.presses)
    }

    @Test
    fun `Codex presses shift tab once from plan back to normal and verifies the result`() {
        val toggler = FakeCodexToggler(ClaudeCodeMode.PLAN)

        val result = cycleToCodexMode(ClaudeCodeMode.NORMAL, toggler)

        assertEquals(CodexModeCycleResult(ClaudeCodeMode.NORMAL, 1), result)
        assertEquals(1, toggler.presses)
    }

    @Test
    fun `Codex already in normal does not toggle into plan`() {
        val toggler = FakeCodexToggler(ClaudeCodeMode.NORMAL)

        val result = cycleToCodexMode(ClaudeCodeMode.NORMAL, toggler)

        assertEquals(CodexModeCycleResult(ClaudeCodeMode.NORMAL, 0), result)
        assertEquals(0, toggler.presses)
    }

    @Test
    fun `Codex rejects Claude-only permission modes before sending anything`() {
        val toggler = FakeCodexToggler(ClaudeCodeMode.NORMAL)

        assertThrows(IllegalArgumentException::class.java) {
            cycleToCodexMode(ClaudeCodeMode.ACCEPT_EDITS, toggler)
        }
        assertEquals(0, toggler.presses)
    }

    private class FakeCycler(
        private val cycle: List<ClaudeCodeMode>,
        start: ClaudeCodeMode,
        /** Presses after which the input box is no longer on screen. */
        private val unreadableFrom: Int = Int.MAX_VALUE,
        /** Reads after a press that come back empty before the repaint lands. */
        private var flakyReads: Int = 0,
    ) : ClaudeModeCycler {
        var presses = 0
            private set
        var settles = 0
            private set

        private var index = cycle.indexOf(start)

        override fun readMode(): ClaudeCodeMode? {
            if (presses >= unreadableFrom) return null
            if (presses > 0 && flakyReads > 0) {
                flakyReads--
                return null
            }
            return cycle[index]
        }

        override fun pressCycleKey() {
            presses++
            index = (index + 1) % cycle.size
        }

        override fun settle() {
            settles++
        }
    }

    private class FakeCodexToggler(start: ClaudeCodeMode) : CodexModeToggler {
        private var mode = start
        var presses = 0
            private set

        override fun readMode(): ClaudeCodeMode = mode

        override fun pressCycleKey() {
            presses++
            mode = if (mode == ClaudeCodeMode.PLAN) ClaudeCodeMode.NORMAL else ClaudeCodeMode.PLAN
        }

        override fun settle() = Unit
    }
}
