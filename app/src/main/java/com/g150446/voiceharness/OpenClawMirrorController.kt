package com.g150446.voiceharness

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext

internal const val OPENCLAW_MIRROR_POLL_MS = 3_000L
private const val OPENCLAW_MIRROR_ERROR_AFTER_FAILURES = 2
private const val OPENCLAW_MIRROR_MAX_BACKOFF_MS = 15_000L
private const val OPENCLAW_THINKING_TEXT = "OpenClaw\n\n考え中…"

/**
 * While OpenClaw mode is on and the G2 plugin is polling, mirrors the OpenClaw session
 * transcript — the same one the in-app chat screen shows — onto the glass.
 * Analogue of [HarborMirrorController]; it only reads, never sends.
 */
internal class OpenClawMirrorController(
    private val scope: CoroutineScope,
    private val loadHistory: suspend () -> Result<List<OpenClawHistoryMessage>>,
    private val voiceState: () -> VoiceState,
    private val g2Active: () -> Boolean = EvenG2ReadingSession::isClientActive,
    private val publishConversation: (String) -> Unit = EvenG2ReadingSession::publishConversation,
    private val publishStatus: (String) -> Unit = EvenG2ReadingSession::publishResponse,
    private val dispatcher: CoroutineContext = Dispatchers.IO,
    /** True while something outside the voice state owns the glass (e.g. a send-confirm prompt). */
    private val glassOwned: () -> Boolean = { false },
) {
    @Volatile private var mode = InteractionMode.AI
    private var job: Job? = null
    private val fetchLock = Mutex()
    private var thinkingShown = false
    private var lastErrorShown: String? = null
    private var failures = 0

    fun setMode(value: InteractionMode) {
        mode = value
        if (value != InteractionMode.OPENCLAW) resetTransientState()
        reconcile()
    }

    fun setG2Active(active: Boolean) {
        if (active) reconcile() else stopPolling()
    }

    /**
     * Shows the question that was just sent with "考え中…" so the glass reflects a send at once,
     * whatever the input (Node voice, in-app mic, in-app text). The poll stays quiet while the
     * voice pipeline is waiting for the reply.
     */
    fun showPending(user: String) {
        thinkingShown = true
        publishStatus(openClawPendingG2Text(user))
    }

    /**
     * Shows the conversation right after a reply, merging in [user]/[reply] if the Gateway has not
     * persisted them yet. Returns false when the glass could not be updated (caller falls back).
     */
    suspend fun showNow(user: String?, reply: String?): Boolean = fetchLock.withLock {
        val history = loadHistory().getOrElse {
            Log.w(TAG, "History load failed: ${it.javaClass.simpleName}")
            return@withLock false
        }
        val text = openClawConversationG2Text(mergeLatestExchange(history, user, reply))
            ?: return@withLock false
        resetTransientState()
        publishConversation(text)
        true
    }

    private fun reconcile() {
        if (mode != InteractionMode.OPENCLAW || !g2Active()) {
            stopPolling()
            return
        }
        if (job?.isActive == true) return
        job = scope.launch(dispatcher) { poll() }
    }

    private suspend fun poll() {
        try {
            while (mode == InteractionMode.OPENCLAW && g2Active()) {
                if (!holdForVoicePipeline() && !glassOwned()) fetchLock.withLock { pollOnce() }
                val wait = if (failures == 0) {
                    OPENCLAW_MIRROR_POLL_MS
                } else {
                    (OPENCLAW_MIRROR_POLL_MS shl failures.coerceAtMost(3))
                        .coerceAtMost(OPENCLAW_MIRROR_MAX_BACKOFF_MS)
                }
                delay(wait)
            }
        } finally {
            // A newer poll may already own `job` after a stop/start; only clear our own.
            if (job === currentCoroutineContext()[Job]) job = null
        }
    }

    private suspend fun pollOnce() {
        val result = loadHistory()
        // The pipeline may have started while the request was in flight; its own output wins.
        if (isVoicePipelineBusy(voiceState()) || glassOwned()) return
        val history = result.getOrNull()
        if (history == null) {
            val message = result.exceptionOrNull()?.message ?: "接続できません"
            Log.w(TAG, "History poll failed: ${result.exceptionOrNull()?.javaClass?.simpleName}")
            failures += 1
            if (failures >= OPENCLAW_MIRROR_ERROR_AFTER_FAILURES && message != lastErrorShown) {
                lastErrorShown = message
                publishStatus("OpenClaw\n\n${message.take(80)}")
            }
            return
        }
        failures = 0
        lastErrorShown = null
        openClawConversationG2Text(history)?.let(publishConversation)
    }

    /**
     * Recording / transcribing / waiting for the reply: don't repaint over what the pipeline
     * shows. Announces the wait once so the glass is not blank for a slow reply.
     */
    private fun holdForVoicePipeline(): Boolean {
        val state = voiceState()
        if (!isVoicePipelineBusy(state)) {
            thinkingShown = false
            return false
        }
        if (state == VoiceState.RESPONDING && !thinkingShown) {
            thinkingShown = true
            publishStatus(OPENCLAW_THINKING_TEXT)
        }
        return true
    }

    private fun resetTransientState() {
        thinkingShown = false
        lastErrorShown = null
        failures = 0
    }

    private fun stopPolling() {
        job?.cancel()
        job = null
    }

    private companion object {
        const val TAG = "OpenClawMirror"
    }
}

internal fun isVoicePipelineBusy(state: VoiceState): Boolean = when (state) {
    VoiceState.RECORDING, VoiceState.TRANSCRIBING, VoiceState.RESPONDING -> true
    VoiceState.READY, VoiceState.ERROR, VoiceState.SPEAKING -> false
}
