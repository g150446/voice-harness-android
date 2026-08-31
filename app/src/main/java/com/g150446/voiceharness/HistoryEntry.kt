package com.g150446.voiceharness

/** Human verdict on whether the recording gesture was meant. */
enum class GestureLabel {
    /** The user performed the gesture on purpose. */
    INTENTIONAL,

    /** The node fired on everyday arm motion. */
    ACCIDENTAL,
    ;

    companion object {
        fun fromStorage(value: String?): GestureLabel? =
            entries.firstOrNull { it.name == value }
    }
}

data class HistoryEntry(
    val id: String,
    val timestamp: Long,
    val transcription: String,
    val response: String,
    val isSilent: Boolean,
    val errorMessage: String,
    /** Gesture milestones for this recording session (measured values from FW). */
    val gestureDiags: List<GestureDiagEntry> = emptyList(),
    /**
     * File name under [GestureTrajectoryStore.DIR_NAME] holding the 6-axis IMU
     * trajectory for this attempt, or null when capture was off. The samples
     * themselves stay out of SharedPreferences: ~30 KB each would not survive
     * a 100-entry history.
     */
    val trajectoryFile: String? = null,
    /** Training label, set by hand from the history screen. */
    val gestureLabel: GestureLabel? = null,
)
