package com.g150446.voiceharness

import com.g150446.voiceharness.assistant.AssistantChatMessage
import com.g150446.voiceharness.assistant.AssistantHistoryMerge
import com.g150446.voiceharness.assistant.AssistantPhase
import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantHistoryMergeTest {
    private val local = listOf(AssistantChatMessage("l", "user", "local"))
    private val fetched = listOf(AssistantChatMessage("h", "user", "remote"))

    @Test
    fun `gateway history replaces local messages when idle`() {
        assertEquals(fetched, AssistantHistoryMerge.merge(local, fetched, AssistantPhase.IDLE))
    }

    @Test
    fun `local messages are kept while a turn is generating`() {
        assertEquals(local, AssistantHistoryMerge.merge(local, fetched, AssistantPhase.GENERATING))
    }

    @Test
    fun `empty history never wipes local messages`() {
        assertEquals(local, AssistantHistoryMerge.merge(local, emptyList(), AssistantPhase.IDLE))
    }
}
