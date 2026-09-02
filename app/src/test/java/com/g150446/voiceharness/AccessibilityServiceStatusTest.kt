package com.g150446.voiceharness

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityServiceStatusTest {

    private val ours =
        "com.g150446.voiceharness/com.g150446.voiceharness.RingAccessibilityService"

    @Test
    fun `empty or blank setting is disabled`() {
        assertFalse(isAccessibilityServiceListed(null, ours))
        assertFalse(isAccessibilityServiceListed("", ours))
        assertFalse(isAccessibilityServiceListed("   ", ours))
    }

    @Test
    fun `other services only is disabled`() {
        assertFalse(
            isAccessibilityServiceListed(
                "com.other/.Service:com.foo/.Bar",
                ours,
            ),
        )
    }

    @Test
    fun `exact flat component is enabled`() {
        assertTrue(isAccessibilityServiceListed(ours, ours))
    }

    @Test
    fun `listed among colon separated services is enabled`() {
        val setting =
            "com.other/.A:$ours:com.google.android.apps.accessibility.voiceaccess/.VoiceAccessService"
        assertTrue(isAccessibilityServiceListed(setting, ours))
    }

    @Test
    fun `case insensitive package match is enabled`() {
        val mixed =
            "com.G150446.voiceharness/com.g150446.voiceharness.RingAccessibilityService"
        assertTrue(isAccessibilityServiceListed(mixed, ours))
    }
}
