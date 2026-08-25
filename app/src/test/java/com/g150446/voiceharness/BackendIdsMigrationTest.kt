package com.g150446.voiceharness

import org.junit.Assert.assertEquals
import org.junit.Test

class BackendIdsMigrationTest {
    @Test
    fun `profile maps to matching stt and llm ids`() {
        assertEquals(SttBackendId.GEMMA, SttBackendId.fromProfile(OnDeviceProfile.GEMMA))
        assertEquals(LlmBackendId.GEMMA, LlmBackendId.fromProfile(OnDeviceProfile.GEMMA))
        assertEquals(SttBackendId.QWEN, SttBackendId.fromProfile(OnDeviceProfile.QWEN))
        assertEquals(LlmBackendId.QWEN, LlmBackendId.fromProfile(OnDeviceProfile.QWEN))
        assertEquals(SttBackendId.GROQ, SttBackendId.fromProfile(OnDeviceProfile.GROQ))
        assertEquals(LlmBackendId.GROQ, LlmBackendId.fromProfile(OnDeviceProfile.GROQ))
    }

    @Test
    fun `storage defaults to gemma`() {
        assertEquals(SttBackendId.GEMMA, SttBackendId.fromStorage(null))
        assertEquals(LlmBackendId.GEMMA, LlmBackendId.fromStorage(null))
        assertEquals(LlmBackendId.OPENROUTER, LlmBackendId.fromStorage("OPENROUTER"))
    }

    @Test
    fun `openrouter is llm only`() {
        assertEquals(true, LlmBackendId.OPENROUTER.isCloud)
        assertEquals(false, SttBackendId.entries.any { it.name == "OPENROUTER" })
    }
}
