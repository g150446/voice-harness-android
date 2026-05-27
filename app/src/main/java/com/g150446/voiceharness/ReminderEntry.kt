package com.g150446.voiceharness

import java.util.UUID

/**
 * Represents a user reminder with a title and scheduled time.
 */
data class ReminderEntry(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val scheduledAtMillis: Long,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val isCompleted: Boolean = false,
    val isTtsEnabled: Boolean = false
)
