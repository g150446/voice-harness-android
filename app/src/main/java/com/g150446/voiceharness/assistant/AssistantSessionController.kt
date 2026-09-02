package com.g150446.voiceharness.assistant

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import com.g150446.voiceharness.BleConnectionService
import com.g150446.voiceharness.QueryOrigin
import com.g150446.voiceharness.ScreenContext
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide controller bridging VoiceInteractionSession, Assistant Activity,
 * and BleConnectionService.
 */
object AssistantSessionController {
    private const val TAG = "AssistantSessionCtrl"
    private const val SCREEN_WAIT_MS = 500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()

    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    private var conversationId: String = ""
    private var activeRequestId: String? = null
    private var screenToken: String? = null
    private var pendingAssistText: String? = null
    private var pendingSourcePackage: String? = null
    private var pendingSourceUri: String? = null
    private var pendingJpeg: ByteArray? = null
    private var assistReceived = false
    private var screenshotReceived = false
    private var sessionOpen = false
    private var finishSession: (() -> Unit)? = null
    private var submitJob: Job? = null
    private val cancelledRequests = mutableSetOf<String>()

    fun beginSession(context: Context, onFinishSession: () -> Unit) {
        // Synchronous so the Activity can attach immediately after onShow.
        runCatching {
            // Best-effort: drop prior session without blocking the binder thread long.
            sessionOpen = false
            ScreenContextStore.remove(screenToken)
            screenToken = null
            pendingAssistText = null
            pendingSourcePackage = null
            pendingSourceUri = null
            pendingJpeg = null
            assistReceived = false
            screenshotReceived = false
            conversationId = "digital-assistant-${UUID.randomUUID()}"
            sessionOpen = true
            finishSession = onFinishSession
            val locked = isDeviceLocked(context)
            _uiState.value = AssistantUiState(
                sessionActive = true,
                conversationId = conversationId,
                locked = locked,
                useScreenContext = !locked,
                phase = AssistantPhase.IDLE,
                statusText = if (locked) "ロック中" else "待機中",
            )
            Log.i(TAG, "Session begun id=$conversationId locked=$locked")
        }.onFailure { Log.e(TAG, "beginSession failed", it) }
    }

    fun attachFinishHandler(onFinishSession: () -> Unit) {
        finishSession = onFinishSession
    }

    fun onHandleAssist(
        data: Bundle?,
        structure: android.app.assist.AssistStructure?,
        interaction: Any?,
    ) {
        scope.launch {
            mutex.withLock {
                if (!sessionOpen) return@withLock
                if (_uiState.value.locked) {
                    clearScreenLocked()
                    assistReceived = true
                    return@withLock
                }
                val extracted = AssistStructureExtractor.extract(structure)
                val incoming = AssistCandidateSelector.Candidate(
                    text = extracted.text.takeIf { it.isNotBlank() },
                    sourcePackage = extracted.sourcePackage
                        ?: data?.getString(IntentKeys.SOURCE_PACKAGE),
                    sourceUri = extracted.sourceUri,
                )
                val current = AssistCandidateSelector.Candidate(
                    text = pendingAssistText,
                    sourcePackage = pendingSourcePackage,
                    sourceUri = pendingSourceUri,
                )
                if (AssistCandidateSelector.shouldReplace(current, incoming)) {
                    pendingAssistText = incoming.text
                    pendingSourcePackage = incoming.sourcePackage
                    pendingSourceUri = incoming.sourceUri
                    Log.d(
                        TAG,
                        "Assist kept textLen=${pendingAssistText?.length ?: 0} " +
                            "pkg=$pendingSourcePackage",
                    )
                } else {
                    Log.d(
                        TAG,
                        "Assist ignored textLen=${incoming.text?.length ?: 0} " +
                            "pkg=${incoming.sourcePackage} (kept pkg=$pendingSourcePackage)",
                    )
                }
                assistReceived = true
                publishScreenAvailabilityLocked()
            }
        }
    }

    fun onHandleScreenshot(screenshot: Bitmap?) {
        scope.launch {
            mutex.withLock {
                if (!sessionOpen) return@withLock
                if (_uiState.value.locked) {
                    clearScreenLocked()
                    screenshotReceived = true
                    return@withLock
                }
                pendingJpeg = ScreenshotEncoder.toJpeg(screenshot)
                screenshotReceived = true
                publishScreenAvailabilityLocked()
                Log.d(TAG, "Screenshot received bytes=${pendingJpeg?.size ?: 0}")
            }
        }
    }

    fun setUseScreenContext(enabled: Boolean) {
        _uiState.update {
            if (!it.screenAvailable) it else it.copy(useScreenContext = enabled)
        }
    }

    fun setDraftText(text: String) {
        _uiState.update { it.copy(draftText = text) }
    }

    fun setListening(listening: Boolean) {
        _uiState.update {
            it.copy(
                phase = if (listening) AssistantPhase.LISTENING else AssistantPhase.IDLE,
                statusText = if (listening) "認識中…" else "待機中",
                errorMessage = null,
            )
        }
    }

    fun setPartialRecognition(text: String) {
        _uiState.update { it.copy(draftText = text, phase = AssistantPhase.RECOGNIZING) }
    }

    fun submitText(context: Context, text: String, fromVoice: Boolean) {
        val query = text.trim()
        if (query.isEmpty()) return
        submitJob?.cancel()
        submitJob = scope.launch(Dispatchers.IO) {
            val requestId = UUID.randomUUID().toString()
            activeRequestId = requestId
            val convId = conversationId
            appendMessage("user", query)
            _uiState.update {
                it.copy(
                    draftText = "",
                    phase = AssistantPhase.GENERATING,
                    statusText = "生成中…",
                    errorMessage = null,
                )
            }

            // Wait briefly for late assist/screenshot callbacks.
            val deadline = System.currentTimeMillis() + SCREEN_WAIT_MS
            while (System.currentTimeMillis() < deadline) {
                val state = mutex.withLock {
                    assistReceived && screenshotReceived
                }
                if (state) break
                delay(50)
            }

            val screen = mutex.withLock {
                if (_uiState.value.locked || !_uiState.value.useScreenContext) {
                    null
                } else {
                    buildScreenContextLocked()
                }
            }

            val token = screen?.let { ScreenContextStore.put(it) }
            screenToken = token

            val origin = if (fromVoice) {
                QueryOrigin.DIGITAL_ASSISTANT_VOICE
            } else {
                QueryOrigin.DIGITAL_ASSISTANT_TEXT
            }

            BleConnectionService.submitAssistantRequest(
                context = context.applicationContext,
                text = query,
                conversationId = convId,
                requestId = requestId,
                speakResponse = fromVoice,
                screenToken = token,
                origin = origin,
            )
        }
    }

    fun onAssistantResult(
        requestId: String?,
        conversationId: String?,
        text: String,
        success: Boolean,
        errorMessage: String? = null,
        speaking: Boolean = false,
    ) {
        if (requestId != null && requestId in cancelledRequests) {
            Log.d(TAG, "Ignoring cancelled request $requestId")
            return
        }
        if (requestId != null && activeRequestId != null && requestId != activeRequestId) {
            Log.d(TAG, "Ignoring stale request $requestId")
            return
        }
        if (!conversationId.isNullOrBlank() && conversationId != this.conversationId) {
            return
        }
        if (!success) {
            _uiState.update {
                it.copy(
                    phase = AssistantPhase.ERROR,
                    statusText = "エラー",
                    errorMessage = errorMessage ?: "応答に失敗しました",
                )
            }
            return
        }
        if (text.isNotBlank()) {
            appendMessage("assistant", text)
        }
        _uiState.update {
            it.copy(
                phase = if (speaking) AssistantPhase.SPEAKING else AssistantPhase.IDLE,
                statusText = if (speaking) "読み上げ中…" else "待機中",
                errorMessage = null,
            )
        }
        if (!speaking) {
            activeRequestId = null
        }
    }

    fun onSpeakingFinished(requestId: String?) {
        if (requestId != null && requestId in cancelledRequests) return
        if (requestId != null && activeRequestId != null && requestId != activeRequestId) return
        _uiState.update {
            it.copy(phase = AssistantPhase.IDLE, statusText = "待機中")
        }
        activeRequestId = null
    }

    fun close(context: Context) {
        scope.launch {
            mutex.withLock {
                cancelInFlightLocked(context)
                teardownLocked(keepFinish = false)
                val finisher = finishSession
                finishSession = null
                finisher?.invoke()
            }
        }
    }

    fun onSessionDestroyed(context: Context) {
        scope.launch {
            mutex.withLock {
                cancelInFlightLocked(context)
                teardownLocked(keepFinish = false)
            }
        }
    }

    private fun cancelInFlightLocked(context: Context) {
        activeRequestId?.let { cancelledRequests += it }
        submitJob?.cancel()
        submitJob = null
        BleConnectionService.cancelAssistantRequest(context, activeRequestId)
        ScreenContextStore.remove(screenToken)
        screenToken = null
        activeRequestId = null
    }

    private fun teardownLocked(keepFinish: Boolean) {
        sessionOpen = false
        conversationId = ""
        pendingAssistText = null
        pendingSourcePackage = null
        pendingSourceUri = null
        pendingJpeg = null
        assistReceived = false
        screenshotReceived = false
        ScreenContextStore.remove(screenToken)
        screenToken = null
        if (!keepFinish) {
            // leave finishSession for close() to call once
        }
        _uiState.value = AssistantUiState()
    }

    private fun clearScreenLocked() {
        pendingAssistText = null
        pendingSourcePackage = null
        pendingSourceUri = null
        pendingJpeg = null
    }

    private fun buildScreenContextLocked(): ScreenContext? {
        val ctx = ScreenContext(
            assistText = pendingAssistText,
            sourcePackage = pendingSourcePackage,
            sourceUri = pendingSourceUri,
            jpegBytes = pendingJpeg,
            capturedAt = System.currentTimeMillis(),
        )
        return if (ctx.isEmpty) null else ctx
    }

    private fun publishScreenAvailabilityLocked() {
        val available = !pendingAssistText.isNullOrBlank() || pendingJpeg != null
        val label = pendingSourcePackage
        _uiState.update {
            it.copy(
                screenAvailable = available,
                sourcePackage = pendingSourcePackage,
                sourceLabel = label,
                useScreenContext = if (available) it.useScreenContext else false,
            )
        }
        // Refresh token content if already stored (optional)
        screenToken?.let { ScreenContextStore.remove(it) }
        screenToken = null
    }

    private fun appendMessage(role: String, content: String) {
        val msg = AssistantChatMessage(
            id = UUID.randomUUID().toString(),
            role = role,
            content = content,
        )
        _uiState.update { it.copy(messages = it.messages + msg) }
    }

    private fun isDeviceLocked(context: Context): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return km?.isKeyguardLocked == true
    }

    private object IntentKeys {
        const val SOURCE_PACKAGE = "android.intent.extra.ASSIST_PACKAGE"
    }
}
