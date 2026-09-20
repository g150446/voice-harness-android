package com.g150446.voiceharness.assistant

internal object AssistantHistoryMerge {
    /**
     * Gateway history wins over the local list because every turn sent from here is persisted
     * there. Keep the local list while a turn is in flight (its user message may not be
     * persisted yet) or when the fetch returned nothing.
     */
    fun merge(
        current: List<AssistantChatMessage>,
        fetched: List<AssistantChatMessage>,
        phase: AssistantPhase,
    ): List<AssistantChatMessage> =
        if (phase == AssistantPhase.GENERATING || fetched.isEmpty()) current else fetched
}
