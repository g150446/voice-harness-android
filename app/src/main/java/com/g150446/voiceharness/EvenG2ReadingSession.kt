package com.g150446.voiceharness

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal data class EvenG2ReadingSnapshot(
    val enabled: Boolean,
    val active: Boolean,
    val revision: Long,
    val bodyText: String?,
    val loading: Boolean,
    val error: String?,
    val doubleTapCount: Long,
)

internal data class EvenG2AdvanceResponse(
    val code: Int,
    val reason: String,
    val accepted: Boolean,
)

/** Process-local, non-persistent reading state shared with the Even Hub WebView. */
internal object EvenG2ReadingSession {
    private const val CLIENT_TIMEOUT_MS = 2_000L

    private data class State(
        val enabled: Boolean = false,
        val active: Boolean = false,
        val revision: Long = 0L,
        val bodyText: String? = null,
        val loading: Boolean = false,
        val error: String? = null,
    )

    private val state = MutableStateFlow(State())

    @Volatile private var lastClientSeenElapsed = 0L

    fun setEnabled(enabled: Boolean) {
        state.update { current ->
            if (enabled) current.copy(enabled = true)
            else State(revision = current.revision + 1L)
        }
        if (!enabled) lastClientSeenElapsed = 0L
    }

    fun publishBody(text: String) {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (normalized.isEmpty()) return
        state.update { current ->
            current.copy(
                enabled = true,
                active = true,
                revision = current.revision + 1L,
                bodyText = normalized,
                loading = false,
                error = null,
            )
        }
    }

    fun snapshot(doubleTapCount: Long): EvenG2ReadingSnapshot {
        val current = state.value
        return EvenG2ReadingSnapshot(
            enabled = current.enabled,
            active = current.active,
            revision = current.revision,
            bodyText = current.bodyText,
            loading = current.loading,
            error = current.error,
            doubleTapCount = doubleTapCount,
        )
    }

    fun markClientSeen() {
        lastClientSeenElapsed = SystemClock.elapsedRealtime()
    }

    fun isClientActive(): Boolean =
        lastClientSeenElapsed > 0L &&
            SystemClock.elapsedRealtime() - lastClientSeenElapsed <= CLIENT_TIMEOUT_MS

    fun beginAdvance(expectedRevision: Long): EvenG2AdvanceResponse {
        val current = state.value
        if (!current.enabled || !current.active || current.bodyText.isNullOrBlank()) {
            return EvenG2AdvanceResponse(404, "Reading session is not active", false)
        }
        if (current.revision != expectedRevision) {
            return EvenG2AdvanceResponse(409, "Reading session revision is stale", false)
        }
        if (current.loading) {
            return EvenG2AdvanceResponse(409, "Reading session is already loading", false)
        }
        state.update { it.copy(loading = true, error = null) }
        return EvenG2AdvanceResponse(202, "Accepted", true)
    }

    fun failAdvance(message: String) {
        state.update { current -> current.copy(loading = false, error = message) }
    }

    fun clearError() {
        state.update { current -> current.copy(error = null) }
    }
}
