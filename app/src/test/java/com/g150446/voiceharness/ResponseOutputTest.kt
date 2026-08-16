package com.g150446.voiceharness

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseOutputTest {

    @Test
    fun parseResponseOutputTarget_defaultsToPhoneAudio() {
        assertEquals(ResponseOutputTarget.PHONE_AUDIO, parseResponseOutputTarget(null))
        assertEquals(ResponseOutputTarget.PHONE_AUDIO, parseResponseOutputTarget("UNKNOWN"))
    }

    @Test
    fun parseResponseOutputTarget_restoresSmartGlasses() {
        assertEquals(
            ResponseOutputTarget.SMART_GLASSES,
            parseResponseOutputTarget(ResponseOutputTarget.SMART_GLASSES.name)
        )
    }

    @Test
    fun decideResponseDelivery_usesPhoneForPhoneTarget() {
        val decision = decideResponseDelivery(
            target = ResponseOutputTarget.PHONE_AUDIO,
            glassesResult = null
        )

        assertFalse(decision.useSmartGlasses)
        assertNull(decision.fallbackMessage)
    }

    @Test
    fun decideResponseDelivery_suppressesAudioWhenGlassesStarted() {
        val decision = decideResponseDelivery(
            target = ResponseOutputTarget.SMART_GLASSES,
            glassesResult = SmartGlassesDisplayResult.Started
        )

        assertTrue(decision.useSmartGlasses)
        assertNull(decision.fallbackMessage)
    }

    @Test
    fun decideResponseDelivery_fallsBackWhenGlassesFailed() {
        val decision = decideResponseDelivery(
            target = ResponseOutputTarget.SMART_GLASSES,
            glassesResult = SmartGlassesDisplayResult.Failed("not connected")
        )

        assertFalse(decision.useSmartGlasses)
        assertEquals(SMART_GLASSES_FALLBACK_MESSAGE, decision.fallbackMessage)
    }

    @Test
    fun awaitSmartGlassesControl_waitsForConfirmation() = runBlocking {
        val state = MutableStateFlow(connectedGlassesState())
        launch {
            delay(10)
            state.value = state.value.copy(controlledByMe = true)
        }

        assertTrue(awaitSmartGlassesControl(state, timeoutMs = 500))
    }

    @Test
    fun awaitSmartGlassesControl_stopsWaitingWhenConnectionIsLost() = runBlocking {
        val state = MutableStateFlow(connectedGlassesState())
        launch {
            delay(10)
            state.value = state.value.copy(connected = false)
        }

        assertFalse(awaitSmartGlassesControl(state, timeoutMs = 500))
    }

    private fun connectedGlassesState() = SmartGlassesState(
        available = true,
        linked = true,
        connected = true
    )
}
