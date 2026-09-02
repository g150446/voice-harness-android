package com.g150446.voiceharness

import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal enum class EvenG2DisplayMode {
    IDLE,
    RESPONSE,
    READING,
}

internal data class EvenG2ReadingSnapshot(
    val enabled: Boolean,
    val active: Boolean,
    val mode: EvenG2DisplayMode,
    val revision: Long,
    val bodyText: String?,
    val loading: Boolean,
    val error: String?,
    val doubleTapCount: Long,
    val singleTapCount: Long = 0L,
)

internal data class EvenG2AdvanceResponse(
    val code: Int,
    val reason: String,
    val accepted: Boolean,
)

/** Process-local, non-persistent display state shared with the Even Hub WebView. */
internal object EvenG2ReadingSession {
    private const val CLIENT_TIMEOUT_MS = 2_000L
    private const val CLIENT_WAIT_MS = 1_500L
    private const val CLIENT_POLL_MS = 100L

    private data class State(
        val enabled: Boolean = false,
        val active: Boolean = false,
        val mode: EvenG2DisplayMode = EvenG2DisplayMode.IDLE,
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

    fun publishBody(text: String) = publishReading(text)

    fun publishReading(text: String) {
        val normalized = normalize(text) ?: return
        state.update { current ->
            current.copy(
                enabled = true,
                active = true,
                mode = EvenG2DisplayMode.READING,
                revision = current.revision + 1L,
                bodyText = normalized,
                loading = false,
                error = null,
            )
        }
    }

    fun publishResponse(text: String) {
        val normalized = normalize(text) ?: return
        state.update { current ->
            current.copy(
                active = true,
                mode = EvenG2DisplayMode.RESPONSE,
                revision = current.revision + 1L,
                bodyText = normalized,
                loading = false,
                error = null,
            )
        }
    }

    fun clearDisplay() {
        state.update { current ->
            if (!current.active && current.mode == EvenG2DisplayMode.IDLE && current.bodyText == null) {
                current
            } else {
                current.copy(
                    active = false,
                    mode = EvenG2DisplayMode.IDLE,
                    revision = current.revision + 1L,
                    bodyText = null,
                    loading = false,
                    error = null,
                )
            }
        }
    }

    fun snapshot(
        doubleTapCount: Long = 0L,
        singleTapCount: Long = 0L,
    ): EvenG2ReadingSnapshot {
        val current = state.value
        return EvenG2ReadingSnapshot(
            enabled = current.enabled,
            active = current.active,
            mode = current.mode,
            revision = current.revision,
            bodyText = current.bodyText,
            loading = current.loading,
            error = current.error,
            doubleTapCount = doubleTapCount,
            singleTapCount = singleTapCount,
        )
    }

    fun uiSmartGlassesState(doubleTapCount: Long = 0L): SmartGlassesState {
        val current = state.value
        val clientActive = isClientActive()
        val readingActive = current.active && current.mode == EvenG2DisplayMode.READING
        val responseActive = current.active && current.mode == EvenG2DisplayMode.RESPONSE
        return SmartGlassesState(
            available = true,
            linked = clientActive || lastClientSeenElapsed > 0L,
            connected = clientActive,
            controlledByMe = clientActive,
            displaying = responseActive,
            deviceName = "Even G2",
            errorMessage = current.error,
            readingPassthroughActive = readingActive,
            readingPage = if (readingActive) 1 else 0,
            readingPageCount = if (readingActive) 1 else 0,
            readingPageLoading = readingActive && current.loading,
        )
    }

    fun markClientSeen() {
        lastClientSeenElapsed = SystemClock.elapsedRealtime()
    }

    fun isClientActive(): Boolean =
        lastClientSeenElapsed > 0L &&
            SystemClock.elapsedRealtime() - lastClientSeenElapsed <= CLIENT_TIMEOUT_MS

    suspend fun displayResponse(text: String): SmartGlassesDisplayResult {
        val normalized = normalize(text)
            ?: return SmartGlassesDisplayResult.Failed("表示する返答がありません")
        publishResponse(normalized)
        if (awaitClientActive(CLIENT_WAIT_MS)) {
            return SmartGlassesDisplayResult.Started
        }
        clearDisplay()
        return SmartGlassesDisplayResult.Failed("Even G2プラグインが接続されていません")
    }

    suspend fun awaitClientActive(timeoutMs: Long = CLIENT_WAIT_MS): Boolean {
        if (isClientActive()) return true
        var waited = 0L
        while (waited < timeoutMs) {
            delay(CLIENT_POLL_MS)
            waited += CLIENT_POLL_MS
            if (isClientActive()) return true
        }
        return isClientActive()
    }

    fun beginAdvance(expectedRevision: Long): EvenG2AdvanceResponse {
        val current = state.value
        if (
            !current.enabled ||
            !current.active ||
            current.mode != EvenG2DisplayMode.READING ||
            current.bodyText.isNullOrBlank()
        ) {
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

    private fun normalize(text: String): String? {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim()
        return normalized.ifEmpty { null }
    }
}
