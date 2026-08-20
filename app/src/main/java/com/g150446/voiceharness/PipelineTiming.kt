package com.g150446.voiceharness

import java.util.Locale

internal enum class PipelineTimingEvent {
    INCOMPLETE_CAPTURE,
    SILENT_SKIP,
    SPEECH_ACCEPTED,
    WAV_FAILURE,
    MODEL_READY_FAILURE,
    ASR_FAILURE,
    ASR_GARBAGE,
    CHAT_FAILURE,
    EXCEPTION,
    CHAT_RESPONSE,
    CONVERSATION_RESET_CONFIRMATION,
    REMINDER_CONFIRMATION,
    REMINDER_ERROR
}

internal enum class PipelineTimingAction {
    START,
    COMMIT,
    DISCARD,
    NONE
}

internal fun pipelineTimingAction(event: PipelineTimingEvent): PipelineTimingAction = when (event) {
    PipelineTimingEvent.SPEECH_ACCEPTED -> PipelineTimingAction.START
    PipelineTimingEvent.INCOMPLETE_CAPTURE,
    PipelineTimingEvent.SILENT_SKIP -> PipelineTimingAction.NONE
    PipelineTimingEvent.CHAT_RESPONSE,
    PipelineTimingEvent.CONVERSATION_RESET_CONFIRMATION,
    PipelineTimingEvent.REMINDER_CONFIRMATION,
    PipelineTimingEvent.REMINDER_ERROR -> PipelineTimingAction.COMMIT
    PipelineTimingEvent.WAV_FAILURE,
    PipelineTimingEvent.MODEL_READY_FAILURE,
    PipelineTimingEvent.ASR_FAILURE,
    PipelineTimingEvent.ASR_GARBAGE,
    PipelineTimingEvent.CHAT_FAILURE,
    PipelineTimingEvent.EXCEPTION -> PipelineTimingAction.DISCARD
}

internal fun formatPipelineTiming(ms: Long): String =
    "処理時間: ${"%.1f".format(Locale.US, ms / 1000.0)}秒"

internal class PipelineTimingTracker {
    var lastCommittedMs: Long = 0L
        private set
    private var startedAtElapsedMs: Long? = null

    fun isRunning(): Boolean = startedAtElapsedMs != null

    fun start(nowElapsedMs: Long) {
        startedAtElapsedMs = nowElapsedMs
    }

    fun commit(nowElapsedMs: Long): Long? {
        val started = startedAtElapsedMs ?: return null
        val elapsed = (nowElapsedMs - started).coerceAtLeast(0L)
        startedAtElapsedMs = null
        lastCommittedMs = elapsed
        return elapsed
    }

    fun discard() {
        startedAtElapsedMs = null
    }

    fun discardIfRunning() {
        if (isRunning()) discard()
    }
}
