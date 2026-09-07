package com.g150446.voiceharness

import org.junit.Assert.assertEquals
import org.junit.Test

class HarborDisplayFilterTest {
    @Test
    fun `blank and separator-only terminal rows are removed`() {
        val input = """

            ─────────────────
            実行中
            . . .
            ........
            error: test failed
            --- result ---
        """.trimIndent()

        assertEquals(
            "実行中\nerror: test failed\n--- result ---",
            filterHarborDisplayText(input),
        )
    }
}
