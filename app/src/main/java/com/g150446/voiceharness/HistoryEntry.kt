package com.g150446.voiceharness

data class HistoryEntry(
    val id: String,
    val timestamp: Long,
    val transcription: String,
    val response: String,
    val isSilent: Boolean,
    val errorMessage: String,
    /** Gesture milestones for this recording session (measured values from FW). */
    val gestureDiags: List<GestureDiagEntry> = emptyList(),
)
