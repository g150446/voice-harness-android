package com.g150446.voiceharness

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KindlePackageTest {
    @Test
    fun `accepts bare and assist-style kindle package names`() {
        assertTrue(KindlePageTurnController.isKindlePackage("com.amazon.kindle"))
        assertTrue(
            KindlePageTurnController.isKindlePackage(
                "com.amazon.kindle/com.amazon.kcp.reader.StandAloneBookReaderActivity",
            ),
        )
        assertFalse(KindlePageTurnController.isKindlePackage(null))
        assertFalse(KindlePageTurnController.isKindlePackage(""))
        assertFalse(
            KindlePageTurnController.isKindlePackage(
                "com.motorola.launcher.secondarydisplay/SecondaryDisplayLauncher",
            ),
        )
    }
}
