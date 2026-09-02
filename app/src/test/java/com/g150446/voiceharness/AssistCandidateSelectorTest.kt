package com.g150446.voiceharness

import com.g150446.voiceharness.assistant.AssistCandidateSelector
import com.g150446.voiceharness.assistant.AssistCandidateSelector.Candidate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistCandidateSelectorTest {
    @Test
    fun `kindle body beats secondary display launcher`() {
        val kindle = Candidate(
            text = "a".repeat(709),
            sourcePackage = "com.amazon.kindle/com.amazon.kcp.reader.StandAloneBookReaderActivity",
            sourceUri = null,
        )
        val secondary = Candidate(
            text = "a".repeat(197),
            sourcePackage =
                "com.motorola.launcher.secondarydisplay/com.motorola.launcher.secondarydisplay.SecondaryDisplayLauncher",
            sourceUri = null,
        )
        assertTrue(AssistCandidateSelector.shouldReplace(null, kindle))
        assertFalse(AssistCandidateSelector.shouldReplace(kindle, secondary))
        assertTrue(AssistCandidateSelector.shouldReplace(secondary, kindle))
    }

    @Test
    fun `longer plain text wins when packages are neutral`() {
        val short = Candidate(text = "short", sourcePackage = "com.example.app", sourceUri = null)
        val long = Candidate(
            text = "much longer body text from the same class of app",
            sourcePackage = "com.example.other",
            sourceUri = null,
        )
        assertTrue(AssistCandidateSelector.shouldReplace(short, long))
        assertFalse(AssistCandidateSelector.shouldReplace(long, short))
    }
}
