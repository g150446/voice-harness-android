package com.g150446.voiceharness

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.g150446.voiceharness.assistant.HeadlessScreenCapture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "VoiceProcessor"
private const val PCM_SAMPLE_RATE = 16000

enum class VoiceState {
    READY,
    RECORDING,
    TRANSCRIBING,
    RESPONDING,
    SPEAKING,
    ERROR
}

internal fun shouldInterruptOnDoubleTap(state: VoiceState): Boolean = when (state) {
    VoiceState.TRANSCRIBING, VoiceState.RESPONDING, VoiceState.SPEAKING -> true
    VoiceState.READY, VoiceState.RECORDING, VoiceState.ERROR -> false
}

/**
 * Whether a single tap should ask the node to start or stop recording via RX.
 * Passthrough mode never uses single tap for recording (G2 page advance only).
 */
internal fun singleTapRecordingCommand(
    passthroughEnabled: Boolean,
    state: VoiceState,
): Byte? {
    if (passthroughEnabled) return null
    return when (state) {
        VoiceState.RECORDING -> BLE_RX_STOP_RECORDING
        VoiceState.READY, VoiceState.ERROR, VoiceState.SPEAKING,
        VoiceState.TRANSCRIBING, VoiceState.RESPONDING -> BLE_RX_START_RECORDING
    }
}

/** Passthrough double-tap: ON→OFF exit, OFF→ON enter (when not interrupting). */
internal enum class PassthroughDoubleTapAction {
    NONE,
    ENABLE,
    DISABLE,
}

internal fun passthroughDoubleTapAction(
    passthroughEnabled: Boolean,
    state: VoiceState,
): PassthroughDoubleTapAction {
    if (shouldInterruptOnDoubleTap(state)) return PassthroughDoubleTapAction.NONE
    return if (passthroughEnabled) {
        PassthroughDoubleTapAction.DISABLE
    } else {
        PassthroughDoubleTapAction.ENABLE
    }
}
private const val PCM_CHANNELS = 1
private const val PCM_BITS_PER_SAMPLE = 16
private const val KINDLE_CAPTURE_ATTEMPTS = 3
private const val KINDLE_CAPTURE_DELAY_MS = 450L

/**
 * Owns the audio processing pipeline: PCM buffering → VAD → on-device STT/LLM → TTS.
 * Profile (Gemma default / Qwen) is selected via Model Settings.
 */
internal class VoiceProcessor(
    private val appContext: Context,
    private val scope: CoroutineScope,
) : TextToSpeech.OnInitListener {

    private val historyRepository = HistoryRepository(appContext)
    private val reminderRepository = ReminderRepository(appContext)
    private val aiFacade = OnDeviceAiFacade(appContext)
    private val aiBackend: VoiceAiBackend get() = aiFacade
    private val assistantGateway: AssistantGateway = BackendAssistantGateway(aiFacade)

    private val pcmBuffer = ByteArrayOutputStream()
    private var isCollectingPcm = false
    private var recordingStartedAtElapsedMs = 0L
    /** Wall-clock start of the current BLE recording (for gesture diag correlation). */
    private var recordingStartedAtWallMs = 0L
    /** Gesture milestones captured at recording stop; attached to the next HistoryEntry. */
    private var pendingGestureDiags: List<GestureDiagEntry> = emptyList()
    /**
     * Wall clock of the last recording stop, used to claim the matching
     * trajectory when the history entry is finally written.
     */
    private var recordingStoppedAtWallMs: Long = 0L
    /** Screen snapshot captured at BLE recording start (HarnessNode path). */
    private val pendingHarnessScreen = AtomicReference<ScreenContext?>(null)
    private val readingPageTurnInFlight = AtomicBoolean(false)
    private val readingInitialCaptureInFlight = AtomicBoolean(false)
    @Volatile private var readingSourceContext: ScreenContext? = null
    @Volatile private var readingPageTurnGesture = PageTurnGesture.UNKNOWN
    private var harnessScreenCaptureJob: Job? = null
    private var harnessPipelineJob: Job? = null
    private val harnessPipelineInterrupted = AtomicBoolean(false)
    private val reminderMutationLock = Any()
    private val activeReminderId = AtomicReference<String?>(null)
    private val pipelineTiming = PipelineTimingTracker()

    private val sileroVad: SileroVad? = try {
        SileroVad(appContext)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to load Silero VAD — VAD disabled", e)
        null
    }

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var responseLanguageCode: String? = null
    private val recordingCuePlayer = RecordingCuePlayer(appContext)

    init {
        tts = TextToSpeech(appContext, this)

        scope.launch(Dispatchers.IO) {
            ModelManager.refresh(appContext)
            aiBackend.ensureReady()
                .onFailure { Log.w(TAG, "Background model warm-up failed: ${it.message}") }
        }

    }

    internal fun handleBleInput(input: BleVoiceInput) {
        when (input) {
            is BleVoiceInput.Audio -> {
                if (isCollectingPcm) {
                    pcmBuffer.write(input.packet.pcmData)
                }
            }
            is BleVoiceInput.Event -> when (input.event) {
                is BleEvent.RecordingStarted -> handleBleRecordingStarted()
                is BleEvent.RecordingStopped -> handleBleRecordingStopped()
                else -> Unit
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val locale = applyTtsLanguage(null)
            ttsReady = true
            Log.d(TAG, "TTS initialized, locale=${locale?.toLanguageTag() ?: "unavailable"}")
        } else {
            Log.e(TAG, "TTS initialization failed: $status")
        }
    }

    // --- Firmware-initiated BLE recording ---

    private fun handleBleRecordingStarted() {
        if (BleConnectionService.voiceState.value == VoiceState.RECORDING) return
        EvenG2ReadingSession.clearDisplay()
        readingSourceContext = null
        readingPageTurnGesture = PageTurnGesture.UNKNOWN
        pcmBuffer.reset()
        sileroVad?.reset()
        recordingStartedAtElapsedMs = SystemClock.elapsedRealtime()
        recordingStartedAtWallMs = System.currentTimeMillis()
        pendingGestureDiags = emptyList()
        harnessPipelineInterrupted.set(false)
        recordingStoppedAtWallMs = 0L
        clearPendingHarnessScreen()
        isCollectingPcm = true
        BleConnectionService.setBleMode(true)
        BleConnectionService.setTranscription("")
        BleConnectionService.setResponse("")
        BleConnectionService.setErrorMessage("")
        if (BleConnectionService.voiceState.value == VoiceState.SPEAKING) tts?.stop()
        BleConnectionService.setVoiceState(VoiceState.RECORDING)
        recordingCuePlayer.playStarted()
        startHarnessScreenCapture()
        Log.d(
            TAG,
            "BLE recording started (firmware-initiated), glasses=${EvenG2ReadingSession.uiSmartGlassesState()}"
        )
    }

    private fun startHarnessScreenCapture() {
        harnessScreenCaptureJob = scope.launch(Dispatchers.IO) {
            val screen = runCatching { HeadlessScreenCapture.capture(appContext) }
                .onFailure { Log.w(TAG, "Harness screen capture failed", it) }
                .getOrNull()
                ?: return@launch
            if (!isActive) return@launch
            val state = BleConnectionService.voiceState.value
            val usable = isCollectingPcm ||
                state == VoiceState.RECORDING ||
                state == VoiceState.TRANSCRIBING ||
                state == VoiceState.RESPONDING
            if (!usable) {
                Log.d(TAG, "Discard late harness screen capture")
                return@launch
            }
            pendingHarnessScreen.set(screen)
            Log.d(
                TAG,
                "Harness screen captured text=${screen.hasText} image=${screen.hasImage} " +
                    "pkg=${screen.sourcePackage}",
            )
        }
    }

    private fun clearPendingHarnessScreen() {
        harnessScreenCaptureJob?.cancel()
        harnessScreenCaptureJob = null
        pendingHarnessScreen.set(null)
    }

    private suspend fun takePendingHarnessScreen(): ScreenContext? {
        withTimeoutOrNull(900L) {
            harnessScreenCaptureJob?.join()
        }
        harnessScreenCaptureJob = null
        return pendingHarnessScreen.getAndSet(null)
    }

    private fun capturePendingGestureDiags() {
        val start = recordingStartedAtWallMs
        val stop = System.currentTimeMillis()
        pendingGestureDiags = GestureDiagStore.snapshotForRecording(start, stop)
        // The trajectory cannot be claimed here. The node sends 0x02 first and
        // only then flushes the batch (main.c, recording-stop handler), which
        // takes about 250 ms of chunked notifications — so at this point it has
        // not arrived. saveHistoryEntry() runs after ASR, by which time it has.
        recordingStoppedAtWallMs = stop
        Log.d(TAG, "Captured ${pendingGestureDiags.size} gesture diags for history")
    }

    private fun saveHistoryEntry(
        transcription: String,
        response: String,
        isSilent: Boolean,
        errorMessage: String,
    ) {
        val id = UUID.randomUUID().toString()
        val trajectory = GestureTrajectoryStore.takeForRecording(recordingStoppedAtWallMs)
        // The node batch was preferred on the assumption that it shares the
        // trajectory's clock. It does not: its t_ms starts at the first push
        // after a clear, measured 548 ms after the trajectory's t0 on
        // session=143. Worse, the node's history buffer is routinely truncated
        // -- that same gesture emitted 24 live diags but flushed a batch of 3,
        // so preferring it threw away most of the sequence. Keep whichever
        // actually carries the run, and write both into the CSV.
        val batch = GestureDiagStore.takeBatchForRecording(recordingStoppedAtWallMs)
        val live = pendingGestureDiags
        val useBatch = batch != null && batch.size >= live.size
        val diags = if (useBatch) batch!! else live
        val trajectoryFile = trajectory?.let {
            GestureTrajectoryStore.write(appContext, id, it, batch, live)
        }
        if (trajectory != null) {
            Log.d(
                TAG,
                "Attached ${trajectory.samples.size} trajectory samples to $id " +
                    "(node batch=${batch?.size ?: 0}, live=${live.size}, " +
                    "kept ${if (useBatch) "node" else "live"})",
            )
        }
        historyRepository.addEntry(
            HistoryEntry(
                id = id,
                timestamp = System.currentTimeMillis(),
                transcription = transcription,
                response = response,
                isSilent = isSilent,
                errorMessage = errorMessage,
                gestureDiags = diags,
                trajectoryFile = trajectoryFile,
                diagsFromNodeBatch = useBatch,
            )
        )
        pendingGestureDiags = emptyList()
        recordingStoppedAtWallMs = 0L
    }

    private fun handleBleRecordingStopped(reason: String = "firmware") {
        if (BleConnectionService.voiceState.value != VoiceState.RECORDING ||
            !BleConnectionService.bleMode.value
        ) return
        isCollectingPcm = false
        sileroVad?.reset()
        val recordingDurationMs = (SystemClock.elapsedRealtime() - recordingStartedAtElapsedMs)
            .coerceAtLeast(0L)
        recordingStartedAtElapsedMs = 0L
        capturePendingGestureDiags()
        recordingStartedAtWallMs = 0L
        val pcmData = pcmBuffer.toByteArray()
        val pcmDurationMs = pcmData.size * 1_000L /
            (PCM_SAMPLE_RATE * PCM_CHANNELS * (PCM_BITS_PER_SAMPLE / 8))
        pcmBuffer.reset()
        BleConnectionService.setBleMode(false)
        Log.d(
            TAG,
            "BLE recording stopped ($reason): wall=${recordingDurationMs}ms, " +
                "pcm=${pcmDurationMs}ms (${pcmData.size} bytes)"
        )

        tts?.stop()
        recordingCuePlayer.playStopped()

        if (BleConnectionService.readingPassthroughEnabled.value) {
            pipelineTiming.start(SystemClock.elapsedRealtime())
            BleConnectionService.setTranscription("パススルーモード")
            BleConnectionService.setResponse("")
            BleConnectionService.setErrorMessage("")
            BleConnectionService.setVoiceState(VoiceState.RESPONDING)
            harnessPipelineJob = scope.launch(Dispatchers.IO) {
                processArmedReadingPassthrough()
            }
            return
        }

        if (!isBlePcmCaptureComplete(recordingDurationMs, pcmDurationMs)) {
            val completeness = if (recordingDurationMs > 0L) {
                pcmDurationMs * 100L / recordingDurationMs
            } else {
                0L
            }
            Log.w(
                TAG,
                "Incomplete BLE PCM capture ($completeness%): refusing ASR to prevent hallucination"
            )
            clearPendingHarnessScreen()
            assistantGateway.resetConversation(HARNESS_CONVERSATION_ID)
            saveHistoryEntry(
                transcription = "",
                response = "",
                isSilent = true,
                errorMessage = "音声データの受信が不完全でした",
            )
            BleConnectionService.setTranscription("")
            BleConnectionService.setResponse("")
            BleConnectionService.setErrorMessage(
                "音声データを十分に受信できませんでした（もう一度話してください）"
            )
            BleConnectionService.setVoiceState(VoiceState.READY)
            return
        }

        if (!hasSpeechInPcm(pcmData)) {
            Log.d(TAG, "VAD: no speech in BLE audio — skipping transcription")
            clearPendingHarnessScreen()
            assistantGateway.resetConversation(HARNESS_CONVERSATION_ID)
            saveHistoryEntry(
                transcription = "",
                response = "",
                isSilent = true,
                errorMessage = "",
            )
            BleConnectionService.setTranscription("")
            BleConnectionService.setResponse("")
            BleConnectionService.setErrorMessage("無音のためスキップしました（もう一度話してください）")
            BleConnectionService.setVoiceState(VoiceState.READY)
            return
        }

        // Leave RECORDING immediately so UI does not appear stuck while VAD/WAV prep finishes.
        pipelineTiming.start(SystemClock.elapsedRealtime())
        BleConnectionService.setVoiceState(VoiceState.TRANSCRIBING)
        val needsColdLoad = ModelManager.status.value.readiness != ModelReadiness.READY
        if (needsColdLoad) {
            BleConnectionService.setErrorMessage(
                "初回はモデル読み込みのため30〜60秒かかることがあります"
            )
        } else {
            BleConnectionService.setErrorMessage("")
        }

        harnessPipelineJob = scope.launch(Dispatchers.IO) {
            val trimmedPcm = PcmSilenceTrimmer.trim(pcmData, PCM_SAMPLE_RATE)
            if (trimmedPcm !== pcmData) {
                val beforeMs = pcmData.size * 1_000L / (PCM_SAMPLE_RATE * PCM_CHANNELS * 2)
                val afterMs = trimmedPcm.size * 1_000L / (PCM_SAMPLE_RATE * PCM_CHANNELS * 2)
                Log.d(TAG, "Trimmed BLE PCM from ${beforeMs}ms to ${afterMs}ms")
            }
            val wavFile = buildWavFile(trimmedPcm) ?: run {
                pipelineTiming.discard()
                clearPendingHarnessScreen()
                BleConnectionService.setErrorMessage("WAV ファイルの作成に失敗しました")
                BleConnectionService.setVoiceState(VoiceState.ERROR)
                return@launch
            }
            transcribeAndRespondOnDevice(wavFile)
        }
    }

    // --- Shared transcription + chat logic ---

    private suspend fun transcribeAndRespondOnDevice(file: File) {
        BleConnectionService.setVoiceState(VoiceState.TRANSCRIBING)
        BleConnectionService.setErrorMessage("")
        responseLanguageCode = null

        try {
            val coldStart = !aiBackend.profile.isCloud &&
                ModelManager.status.value.readiness != ModelReadiness.READY
            if (coldStart) {
                BleConnectionService.setErrorMessage(
                    "初回はモデル読み込みのため30〜60秒かかることがあります"
                )
            }
            val ready = aiBackend.ensureReady()
            if (ready.isFailure) {
                val errMsg = ready.exceptionOrNull()?.message ?: "モデル準備に失敗しました"
                BleConnectionService.setErrorMessage(errMsg)
                BleConnectionService.setVoiceState(VoiceState.ERROR)
                saveHistoryEntry(
                    transcription = "",
                    response = "",
                    isSilent = false,
                    errorMessage = errMsg,
                )
                return
            }
            if (coldStart) {
                BleConnectionService.setErrorMessage("")
            }

            val asr = aiBackend.transcribe(file)
            if (asr.isFailure) {
                val errMsg = "ASR error: ${asr.exceptionOrNull()?.message}"
                BleConnectionService.setErrorMessage(errMsg)
                BleConnectionService.setVoiceState(VoiceState.ERROR)
                saveHistoryEntry(
                    transcription = "",
                    response = "",
                    isSilent = false,
                    errorMessage = errMsg,
                )
                return
            }

            val transcriptionResult = asr.getOrThrow()
            val rawText = transcriptionResult.text.trim()
            Log.d(
                TAG,
                "profile=${aiBackend.profile} backend=${aiBackend.name} ASR latency=${transcriptionResult.latencyMs} ms"
            )

            if (AsrTextFilter.isGarbageOrEmpty(rawText)) {
                Log.w(TAG, "ASR garbage/empty detected: '$rawText' — treating as silent")
                saveHistoryEntry(
                    transcription = "",
                    response = "",
                    isSilent = true,
                    errorMessage = "",
                )
                BleConnectionService.setTranscription("")
                BleConnectionService.setResponse("")
                BleConnectionService.setErrorMessage("発話として認識できませんでした（背景音の可能性）")
                BleConnectionService.setVoiceState(VoiceState.READY)
                return
            }

            val intentVerdict = UtteranceIntentGate.evaluate(
                text = rawText,
                baseLanguage = ModelManager.currentSpeechBaseLanguage(appContext),
                conversationActive =
                    assistantGateway.isConversationActive(HARNESS_CONVERSATION_ID),
            )
            if (intentVerdict.isSuppressed) {
                // The gesture fires on everyday arm motion it cannot be told apart
                // from, so an accidental recording is expected; what must not happen
                // is an LLM call and spoken answer in the middle of a consultation.
                Log.w(TAG, "Suppressed as accidental trigger ($intentVerdict): '$rawText'")
                // Keep the text: tuning the gate needs to know what was misheard.
                saveHistoryEntry(
                    transcription = rawText,
                    response = "",
                    isSilent = false,
                    errorMessage = "誤発火として抑制: ${intentVerdict.name}",
                )
                BleConnectionService.setTranscription(rawText)
                BleConnectionService.setResponse("")
                BleConnectionService.setErrorMessage("誤発火として抑制しました")
                BleConnectionService.setVoiceState(VoiceState.READY)
                return
            }

            val transcribed = rawText.ifBlank { "(音声なし)" }
            responseLanguageCode = SpeechLanguageResolver.resolvePreferredLanguageCode(
                whisperLanguageCode = transcriptionResult.languageCode,
                transcribedText = transcribed
            )
            BleConnectionService.setTranscription(transcribed)
            Log.d(TAG, "Transcription: $transcribed, language=${responseLanguageCode ?: "default"}")

            BleConnectionService.setVoiceState(VoiceState.RESPONDING)

            val resetParse = ConversationResetDetector.parse(transcribed)
            if (resetParse.shouldReset) {
                assistantGateway.resetConversation(HARNESS_CONVERSATION_ID)
                Log.d(TAG, "Conversation context reset by voice command")
                val remaining = resetParse.remainingUserText?.trim().orEmpty()
                if (remaining.isEmpty()) {
                    val confirmation = ConversationResetDetector.confirmationMessage(
                        languageCode = responseLanguageCode,
                        remainingUserText = null
                    )
                    BleConnectionService.setResponse(confirmation)
                    saveHistoryEntry(
                        transcription = transcribed,
                        response = confirmation,
                        isSilent = false,
                        errorMessage = "",
                    )
                    presentResponse(confirmation)
                    return
                }
                BleConnectionService.setTranscription(remaining)
            }

            val query = resetParse.remainingUserText?.trim()
                ?.takeIf { resetParse.shouldReset && it.isNotEmpty() }
                ?: transcribed
            val screenContext = takePendingHarnessScreen()
            val readingPassthrough = ReadingPassthrough.isRequested(query)
            if (readingPassthrough) {
                BleConnectionService.setReadingPassthroughEnabled(appContext, true)
            }
            if (readingPassthrough && screenContext?.isEmpty != false) {
                val message = "画面の本文を取得できませんでした"
                BleConnectionService.setResponse(message)
                saveHistoryEntry(
                    transcription = BleConnectionService.transcription.value,
                    response = message,
                    isSilent = false,
                    errorMessage = message,
                )
                presentResponse(message)
                return
            }
            val chat = assistantGateway.submit(
                AssistantRequest(
                    text = if (readingPassthrough) {
                        ReadingPassthrough.extractionPrompt(query)
                    } else {
                        query
                    },
                    origin = QueryOrigin.HARNESS_NODE_VOICE,
                    conversationId = HARNESS_CONVERSATION_ID,
                    speakResponse = true,
                    languageCode = responseLanguageCode,
                    screenContext = screenContext,
                )
            )
            if (chat.isFailure) {
                val errMsg = "Chat error: ${chat.exceptionOrNull()?.message}"
                BleConnectionService.setErrorMessage(errMsg)
                BleConnectionService.setVoiceState(VoiceState.ERROR)
                saveHistoryEntry(
                    transcription = BleConnectionService.transcription.value,
                    response = "",
                    isSilent = false,
                    errorMessage = errMsg,
                )
                return
            }

            val chatResult = chat.getOrThrow()
            Log.d(
                TAG,
                "profile=${aiBackend.profile} Chat latency=${chatResult.latencyMs} ms tools=${chatResult.toolCalls.size}"
            )

            val reminderCall = chatResult.toolCalls.firstOrNull { it.name == "set_reminder" }
            if (reminderCall != null) {
                handleReminderToolCall(reminderCall.argumentsJson)
            } else {
                val responseText = chatResult.text
                if (readingPassthrough) {
                    // Screen-derived book text is transient: do not retain it in the shared
                    // conversation after the extraction turn.
                    assistantGateway.resetConversation(HARNESS_CONVERSATION_ID)
                    presentReadingPassthrough(query, responseText, screenContext)
                    return
                }
                val finalResponse = responseText.ifBlank { "(返答なし)" }
                BleConnectionService.setResponse(finalResponse)
                Log.d(TAG, "Response: $responseText")
                saveHistoryEntry(
                    transcription = BleConnectionService.transcription.value,
                    response = finalResponse,
                    isSilent = false,
                    errorMessage = "",
                )
                presentResponse(finalResponse)
            }
        } catch (_: CancellationException) {
            Log.d(TAG, "Harness pipeline cancelled by double tap")
        } catch (e: Exception) {
            Log.e(TAG, "Error during on-device transcribe/respond", e)
            val errMsg = "エラー: ${e.message}"
            BleConnectionService.setErrorMessage(errMsg)
            BleConnectionService.setVoiceState(VoiceState.ERROR)
            saveHistoryEntry(
                transcription = BleConnectionService.transcription.value,
                response = "",
                isSilent = false,
                errorMessage = errMsg,
            )
        } finally {
            clearPendingHarnessScreen()
            pipelineTiming.discardIfRunning()
            try { file.delete() } catch (_: Exception) {}
        }
    }

    private val cancelledAssistantRequests = mutableSetOf<String>()
    private var activeAssistantRequestId: String? = null

    /** Legacy headless entry (Harness path / simple voice). */
    internal fun handleAssistantText(text: String, conversationId: String) {
        handleAssistantRequest(
            text = text,
            conversationId = conversationId,
            requestId = null,
            speakResponse = true,
            screenToken = null,
            origin = QueryOrigin.DIGITAL_ASSISTANT_VOICE,
        )
    }

    internal fun handleAssistantRequest(
        text: String,
        conversationId: String,
        requestId: String?,
        speakResponse: Boolean,
        screenToken: String?,
        origin: QueryOrigin,
    ) {
        val query = text.trim()
        if (query.isEmpty()) return
        activeAssistantRequestId = requestId
        harnessPipelineInterrupted.set(false)
        scope.launch(Dispatchers.IO) {
            val screenContext = com.g150446.voiceharness.assistant.ScreenContextStore.take(screenToken)
            BleConnectionService.setTranscription(query)
            BleConnectionService.setResponse("")
            BleConnectionService.setErrorMessage("")
            BleConnectionService.setVoiceState(VoiceState.RESPONDING)
            val ready = aiBackend.ensureReady()
            if (ready.isFailure) {
                val err = ready.exceptionOrNull()?.message ?: "モデル準備に失敗しました"
                BleConnectionService.setErrorMessage(err)
                BleConnectionService.setVoiceState(VoiceState.ERROR)
                BleConnectionService.releaseAssistantProcessing()
                notifyAssistantUi(
                    requestId = requestId,
                    conversationId = conversationId,
                    text = "",
                    success = false,
                    errorMessage = err,
                    speaking = false,
                )
                return@launch
            }
            if (isAssistantCancelled(requestId)) {
                BleConnectionService.releaseAssistantProcessing()
                return@launch
            }
            val language = SpeechLanguageResolver.resolvePreferredLanguageCode(
                whisperLanguageCode = null,
                transcribedText = query,
            )
            responseLanguageCode = language
            val result = assistantGateway.submit(
                AssistantRequest(
                    text = query,
                    origin = origin,
                    requestId = requestId,
                    conversationId = conversationId,
                    speakResponse = speakResponse,
                    screenContext = screenContext,
                    languageCode = language,
                )
            )
            if (isAssistantCancelled(requestId)) {
                BleConnectionService.releaseAssistantProcessing()
                return@launch
            }
            result.onSuccess { reply ->
                val reminderCall = reply.toolCalls.firstOrNull { it.name == "set_reminder" }
                if (reminderCall != null) {
                    handleReminderToolCall(reminderCall.argumentsJson)
                    notifyAssistantUi(
                        requestId = requestId,
                        conversationId = conversationId,
                        text = reply.text.ifBlank { "リマインダーを設定しました" },
                        success = true,
                        speaking = speakResponse,
                    )
                    if (speakResponse) {
                        presentResponse(reply.text.ifBlank { "リマインダーを設定しました" }, requestId)
                    } else {
                        BleConnectionService.setVoiceState(VoiceState.READY)
                        BleConnectionService.releaseAssistantProcessing()
                    }
                    return@onSuccess
                }
                val response = reply.text.ifBlank { "(返答なし)" }
                BleConnectionService.setResponse(response)
                saveHistoryEntry(query, response, isSilent = false, errorMessage = "")
                notifyAssistantUi(
                    requestId = requestId,
                    conversationId = conversationId,
                    text = response,
                    success = true,
                    speaking = speakResponse,
                )
                if (speakResponse) {
                    presentResponse(response, requestId)
                } else {
                    BleConnectionService.setVoiceState(VoiceState.READY)
                    BleConnectionService.releaseAssistantProcessing()
                }
            }.onFailure { error ->
                val err = "Chat error: ${error.message}"
                BleConnectionService.setErrorMessage(err)
                BleConnectionService.setVoiceState(VoiceState.ERROR)
                BleConnectionService.releaseAssistantProcessing()
                notifyAssistantUi(
                    requestId = requestId,
                    conversationId = conversationId,
                    text = "",
                    success = false,
                    errorMessage = err,
                    speaking = false,
                )
            }
        }
    }

    internal fun cancelAssistantRequest(requestId: String?) {
        if (!requestId.isNullOrBlank()) {
            cancelledAssistantRequests += requestId
        }
        aiBackend.cancel()
        stopSpeaking()
        BleConnectionService.releaseAssistantProcessing()
        Log.d(TAG, "Cancelled assistant request id=$requestId")
    }

    private fun isAssistantCancelled(requestId: String?): Boolean {
        if (requestId.isNullOrBlank()) return false
        return requestId in cancelledAssistantRequests
    }

    private fun notifyAssistantUi(
        requestId: String?,
        conversationId: String,
        text: String,
        success: Boolean,
        errorMessage: String? = null,
        speaking: Boolean = false,
    ) {
        com.g150446.voiceharness.assistant.AssistantSessionController.onAssistantResult(
            requestId = requestId,
            conversationId = conversationId,
            text = text,
            success = success,
            errorMessage = errorMessage,
            speaking = speaking,
        )
    }

    // --- VAD helpers (post-stop clip only; stop is firmware gesture TX 0x02) ---

    private fun hasSpeechInPcm(pcmData: ByteArray): Boolean {
        // ~0.15s at 16kHz mono 16-bit — drop click/glitch clips, keep short quiet phrases.
        val minPcmBytes = (PCM_SAMPLE_RATE * PCM_CHANNELS * (PCM_BITS_PER_SAMPLE / 8) * 0.15).toInt()
        if (pcmData.size < minPcmBytes) {
            Log.d(TAG, "VAD: PCM too short (${pcmData.size} < $minPcmBytes bytes) — skip")
            return false
        }
        val analysis = BleSpeechDetector.analyzeBlePcm(pcmData)
        val vad = sileroVad ?: run {
            Log.w(TAG, "Silero VAD unavailable — falling back to spectrum VAD")
            return hasSpeechBySpectrum(analysis.samples, "Silero unavailable")
        }
        val frameSize = SileroVad.FRAME_SIZE
        val nSamples = analysis.samples.size
        if (nSamples < frameSize) {
            Log.d(TAG, "Silero VAD: audio too short ($nSamples samples) — falling back to spectrum VAD")
            return hasSpeechBySpectrum(analysis.samples, "Audio too short for Silero")
        }

        Log.d(
            TAG,
            "Silero VAD: dcOffset=${"%.4f".format(Locale.US, analysis.dcOffset)}, peakBeforeDC=${"%.4f".format(Locale.US, analysis.peakBeforeDc)}, peakAfterDC=${"%.4f".format(Locale.US, analysis.peakAfterDc)}, rmsAfterDC=${"%.4f".format(Locale.US, analysis.rmsAfterDc)}, gain=${"%.1f".format(Locale.US, analysis.gain)}"
        )

        vad.reset()
        var speechFrames = 0
        var totalFrames = 0
        var maxProb = 0f
        val firstFrameProbs = ArrayList<String>(5)
        var offset = 0

        try {
            while (offset + frameSize <= nSamples) {
                val frame = FloatArray(frameSize) { i -> analysis.samples[offset + i] * analysis.gain }
                val prob = vad.predict(frame)
                if (firstFrameProbs.size < 5) {
                    firstFrameProbs += "%.3f".format(Locale.US, prob)
                }
                if (prob > maxProb) maxProb = prob
                if (prob > SILERO_SPEECH_THRESHOLD) speechFrames++
                totalFrames++
                offset += frameSize
            }
        } catch (e: Exception) {
            Log.e(TAG, "Silero VAD inference failed — falling back to spectrum VAD", e)
            return hasSpeechBySpectrum(analysis.samples, "Silero inference error")
        }

        val ratio = if (totalFrames > 0) speechFrames.toDouble() / totalFrames else 0.0
        Log.d(
            TAG,
            "Silero VAD: $speechFrames/$totalFrames speech frames (${"%.1f".format(Locale.US, ratio * 100)}%), maxProb=${"%.3f".format(Locale.US, maxProb)}, firstProbs=${firstFrameProbs.joinToString(prefix = "[", postfix = "]")}"
        )

        val sileroDecision = decideBleSileroOutcome(
            speechFrames = speechFrames,
            totalFrames = totalFrames,
            maxProb = maxProb
        )
        if (sileroDecision.accepted) {
            return true
        }

        Log.w(
            TAG,
            "Silero VAD did not accept BLE audio (reason=${sileroDecision.spectrumReason}, maxProb=${"%.3f".format(Locale.US, maxProb)}, stuck=${sileroDecision.sileroStuck}) — checking spectrum/energy VAD"
        )
        return hasSpeechBySpectrum(
            samples = analysis.samples,
            reason = sileroDecision.spectrumReason ?: "Silero rejected audio",
            peakAfterDc = analysis.peakAfterDc,
            rmsAfterDc = analysis.rmsAfterDc,
            sileroStuck = sileroDecision.sileroStuck
        )
    }

    private fun hasSpeechBySpectrum(
        samples: FloatArray,
        reason: String,
        peakAfterDc: Float? = null,
        rmsAfterDc: Float? = null,
        sileroStuck: Boolean = false
    ): Boolean {
        val result = BleSpeechDetector.detectSpeechBySpectrum(samples, PCM_SAMPLE_RATE)
        val rescued = shouldRescueBleSpectrum(
            peakAfterDc = peakAfterDc,
            rmsAfterDc = rmsAfterDc,
            maxBandRatio = result.maxBandRatio,
            sileroStuck = sileroStuck
        )
        Log.d(
            TAG,
            "Spectrum VAD fallback: reason=$reason, speechFrames=${result.speechFrames}/${result.activeFrames} active (${result.totalFrames} total, ${"%.1f".format(Locale.US, result.ratio * 100)}%), maxBandRatio=${"%.3f".format(Locale.US, result.maxBandRatio)}, sileroStuck=$sileroStuck, rescued=$rescued, topBandRatios=${result.topBandRatios.joinToString(prefix = "[", postfix = "]") { "%.3f".format(Locale.US, it) }}"
        )
        if (result.hasSpeech(BleSpeechDetector.SPEECH_FRAME_MIN_RATIO)) {
            return true
        }
        if (rescued) {
            Log.w(
                TAG,
                "VAD rescue accepted BLE audio: peakAfterDC=${"%.4f".format(Locale.US, peakAfterDc)}, rmsAfterDC=${"%.4f".format(Locale.US, rmsAfterDc)}, maxBandRatio=${"%.3f".format(Locale.US, result.maxBandRatio)}, sileroStuck=$sileroStuck"
            )
            return true
        }
        return false
    }

    // --- Reminder helpers ---

    private suspend fun handleReminderToolCall(argumentsStr: String) {
        val args = try {
            JSONObject(argumentsStr)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse tool call arguments", e)
            return
        }
        val title = args.optString("title", "").trim()
        val datetimeStr = args.optString("datetime", "").trim()
        val ttsEnabled = args.optBoolean("tts_enabled", false)

        if (title.isBlank() || datetimeStr.isBlank()) {
            val errorMsg = "リマインダーの設定に必要な情報が足りませんでした。"
            BleConnectionService.setResponse(errorMsg)
            saveHistoryEntry(
                transcription = BleConnectionService.transcription.value,
                response = errorMsg,
                isSilent = false,
                errorMessage = "",
            )
            presentResponse(errorMsg)
            return
        }

        val scheduledAtMillis = parseIso8601ToMillis(datetimeStr)
        if (scheduledAtMillis == null) {
            val errorMsg = "日時の解析に失敗しました: $datetimeStr"
            BleConnectionService.setResponse(errorMsg)
            saveHistoryEntry(
                transcription = BleConnectionService.transcription.value,
                response = errorMsg,
                isSilent = false,
                errorMessage = "",
            )
            presentResponse(errorMsg)
            return
        }

        val entry = ReminderEntry(
            title = title,
            scheduledAtMillis = scheduledAtMillis,
            isTtsEnabled = ttsEnabled
        )
        activeReminderId.set(entry.id)
        val registered = synchronized(reminderMutationLock) {
            if (harnessPipelineInterrupted.get()) {
                false
            } else {
                reminderRepository.addEntry(entry)
                if (harnessPipelineInterrupted.get()) {
                    reminderRepository.deleteEntry(entry.id)
                    false
                } else {
                    ReminderAlarmScheduler.schedule(appContext, entry)
                    true
                }
            }
        }
        if (!registered) {
            rollbackInterruptedReminder(entry.id)
            activeReminderId.compareAndSet(entry.id, null)
            return
        }

        val confirmation = buildReminderConfirmationText(title, datetimeStr, ttsEnabled)
        synchronized(reminderMutationLock) {
            if (harnessPipelineInterrupted.get()) {
                rollbackInterruptedReminder(entry.id)
                activeReminderId.compareAndSet(entry.id, null)
                return
            }
            BleConnectionService.setResponse(confirmation)
            saveHistoryEntry(
                transcription = BleConnectionService.transcription.value,
                response = confirmation,
                isSilent = false,
                errorMessage = "",
            )
            assistantGateway.resetConversation(HARNESS_CONVERSATION_ID)
            activeReminderId.compareAndSet(entry.id, null)
        }
        presentResponse(confirmation)
    }

    private fun parseIso8601ToMillis(datetimeStr: String): Long? {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
            sdf.parse(datetimeStr)?.time
        } catch (e: Exception) {
            try {
                // Handle Z suffix (UTC) format, e.g., 2025-05-24T14:30:00Z
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                sdf.parse(datetimeStr)?.time
            } catch (e2: Exception) {
                try {
                    // Fallback: no timezone offset, assume Asia/Tokyo
                    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                    sdf.timeZone = TimeZone.getTimeZone("Asia/Tokyo")
                    sdf.parse(datetimeStr)?.time
                } catch (e3: Exception) {
                    null
                }
            }
        }
    }

    private fun buildReminderConfirmationText(title: String, datetimeStr: String, ttsEnabled: Boolean): String {
        val scheduledAtMillis = parseIso8601ToMillis(datetimeStr)
        val scheduledText = if (scheduledAtMillis != null) {
            val displaySdf = SimpleDateFormat("M月d日 H:mm", Locale.JAPAN)
            displaySdf.timeZone = TimeZone.getTimeZone("Asia/Tokyo")
            displaySdf.format(Date(scheduledAtMillis))
        } else {
            datetimeStr
        }
        val ttsText = if (ttsEnabled) "読み上げありで" else ""
        return "${title}のリマインダーを${ttsText}${scheduledText}に設定しました。"
    }

    // --- WAV file builder ---

    private fun buildWavFile(pcmData: ByteArray): File? {
        return try {
            val file = File(appContext.cacheDir, "ble_audio_${System.currentTimeMillis()}.wav")
            val byteRate = PCM_SAMPLE_RATE * PCM_CHANNELS * PCM_BITS_PER_SAMPLE / 8
            val blockAlign = (PCM_CHANNELS * PCM_BITS_PER_SAMPLE / 8).toShort()
            val dataSize = pcmData.size

            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray())
                putInt(36 + dataSize)
                put("WAVE".toByteArray())
                put("fmt ".toByteArray())
                putInt(16)
                putShort(1)
                putShort(PCM_CHANNELS.toShort())
                putInt(PCM_SAMPLE_RATE)
                putInt(byteRate)
                putShort(blockAlign)
                putShort(PCM_BITS_PER_SAMPLE.toShort())
                put("data".toByteArray())
                putInt(dataSize)
            }.array()

            file.outputStream().use { out ->
                out.write(header)
                out.write(pcmData)
            }
            Log.d(TAG, "WAV file: ${file.absolutePath} (${file.length()} bytes)")
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build WAV file", e)
            null
        }
    }

    // --- TTS ---

    private suspend fun presentResponse(text: String, requestId: String? = null) {
        if (isAssistantCancelled(requestId)) {
            BleConnectionService.releaseAssistantProcessing()
            return
        }
        commitPipelineTiming()
        val target = BleConnectionService.responseOutputTarget.value
        val glassesResult = if (target == ResponseOutputTarget.SMART_GLASSES) {
            EvenG2ReadingSession.displayResponse(text)
        } else {
            null
        }
        val decision = decideResponseDelivery(target, glassesResult)
        if (decision.useSmartGlasses) {
            tts?.stop()
            BleConnectionService.setPhonePlaybackActive(false)
            BleConnectionService.setVoiceState(VoiceState.READY)
            BleConnectionService.releaseAssistantProcessing()
            com.g150446.voiceharness.assistant.AssistantSessionController.onSpeakingFinished(requestId)
            Log.d(TAG, "Response routed to Even G2")
            return
        }
        decision.fallbackMessage?.let {
            BleConnectionService.setErrorMessage(it)
            val failure = glassesResult as? SmartGlassesDisplayResult.Failed
            Log.w(TAG, "$it: ${failure?.message}", failure?.cause)
        }
        speakResponse(text, requestId)
    }

    private suspend fun presentReadingPassthrough(
        command: String,
        extracted: String,
        sourceContext: ScreenContext? = null,
        saveHistory: Boolean = true,
        silentFailure: Boolean = false,
        successLabel: String = "読書パススルーを開始しました（G2）",
    ) {
        commitPipelineTiming()
        if (!BleConnectionService.readingPassthroughEnabled.value) {
            BleConnectionService.setVoiceState(VoiceState.READY)
            return
        }
        val extraction = ReadingPassthrough.parseExtraction(extracted)
        val text = extraction.bodyText
        if (text == null) {
            val message = "画面から表示できる本文を抽出できませんでした"
            EvenG2ReadingSession.failAdvance(message)
            if (!silentFailure) {
                BleConnectionService.setResponse(message)
                BleConnectionService.setErrorMessage(message)
                saveHistoryEntry(command, message, isSilent = false, errorMessage = message)
                speakResponse(message)
            } else {
                BleConnectionService.setVoiceState(VoiceState.READY)
                Log.w(TAG, "Auto passthrough extraction empty")
            }
            return
        }

        readingSourceContext = sourceContext
        if (extraction.pageTurnGesture != PageTurnGesture.UNKNOWN) {
            readingPageTurnGesture = extraction.pageTurnGesture
            Log.d(
                TAG,
                "Reading page-turn gesture=$readingPageTurnGesture " +
                    "writing=${extraction.writingDirection}",
            )
        }
        EvenG2ReadingSession.publishReading(text)
        if (EvenG2ReadingSession.isClientActive() || EvenG2ReadingSession.awaitClientActive()) {
            // Extracted screen text stays out of UI/history and lives only in the G2 session.
            BleConnectionService.setResponse(successLabel)
            if (saveHistory) saveHistoryEntry(command, successLabel, isSilent = false, errorMessage = "")
            tts?.stop()
            BleConnectionService.setPhonePlaybackActive(false)
            BleConnectionService.setVoiceState(VoiceState.READY)
            Log.d(TAG, "Reading passthrough started on Even G2 (${text.length} chars)")
            return
        }

        val message = "G2に本文を表示できませんでした: Even G2プラグインが接続されていません"
        EvenG2ReadingSession.clearDisplay()
        if (!silentFailure) {
            BleConnectionService.setResponse(message)
            BleConnectionService.setErrorMessage(message)
            saveHistoryEntry(command, message, isSilent = false, errorMessage = message)
            Log.w(TAG, message)
            speakResponse(message)
        } else {
            BleConnectionService.setVoiceState(VoiceState.READY)
            Log.w(TAG, "Auto passthrough skipped: $message")
        }
    }

    private suspend fun processArmedReadingPassthrough() {
        val command = "ホーム画面からパススルーモード"
        try {
            val screenContext = takePendingHarnessScreen()
            if (!BleConnectionService.readingPassthroughEnabled.value) {
                BleConnectionService.setVoiceState(VoiceState.READY)
                return
            }
            if (screenContext?.isEmpty != false) {
                val message = "画面の本文を取得できませんでした"
                BleConnectionService.setResponse(message)
                BleConnectionService.setErrorMessage(message)
                saveHistoryEntry(command, message, isSilent = false, errorMessage = message)
                BleConnectionService.setVoiceState(VoiceState.READY)
                return
            }
            extractAndPresentReadingPassthrough(command, screenContext)
        } catch (error: Exception) {
            Log.e(TAG, "Armed reading passthrough failed", error)
            val message = "パススルーエラー: ${error.message}"
            BleConnectionService.setResponse(message)
            BleConnectionService.setErrorMessage(message)
            saveHistoryEntry(command, message, isSilent = false, errorMessage = message)
            BleConnectionService.setVoiceState(VoiceState.ERROR)
        } finally {
            clearPendingHarnessScreen()
            pipelineTiming.discardIfRunning()
        }
    }

    private suspend fun extractAndPresentReadingPassthrough(
        command: String,
        screenContext: ScreenContext,
        silentFailure: Boolean = false,
        successLabel: String = "読書パススルーを開始しました（G2）",
    ) {
        val ready = aiBackend.ensureReady()
        if (ready.isFailure) {
            val message = ready.exceptionOrNull()?.message ?: "モデル準備に失敗しました"
            if (!silentFailure) {
                BleConnectionService.setResponse(message)
                BleConnectionService.setErrorMessage(message)
                saveHistoryEntry(command, message, isSilent = false, errorMessage = message)
                BleConnectionService.setVoiceState(VoiceState.ERROR)
            } else {
                BleConnectionService.setVoiceState(VoiceState.READY)
                Log.w(TAG, "Auto passthrough model not ready: $message")
            }
            return
        }

        val result = assistantGateway.submit(
            AssistantRequest(
                text = ReadingPassthrough.extractionPrompt(command),
                origin = QueryOrigin.HARNESS_NODE_VOICE,
                conversationId = HARNESS_CONVERSATION_ID,
                speakResponse = false,
                languageCode = null,
                screenContext = screenContext,
            )
        )
        assistantGateway.resetConversation(HARNESS_CONVERSATION_ID)
        if (!BleConnectionService.readingPassthroughEnabled.value) {
            BleConnectionService.setVoiceState(VoiceState.READY)
            return
        }
        result.onSuccess { reply ->
            presentReadingPassthrough(
                command = command,
                extracted = reply.text,
                sourceContext = screenContext,
                silentFailure = silentFailure,
                successLabel = successLabel,
            )
        }.onFailure { error ->
            val message = "Chat error: ${error.message}"
            if (!silentFailure) {
                BleConnectionService.setResponse(message)
                BleConnectionService.setErrorMessage(message)
                saveHistoryEntry(command, message, isSilent = false, errorMessage = message)
                BleConnectionService.setVoiceState(VoiceState.ERROR)
            } else {
                BleConnectionService.setVoiceState(VoiceState.READY)
                Log.w(TAG, "Auto passthrough chat failed: $message")
            }
        }
    }

    /**
     * Single tap (FW 0.0.94+ notify-only): host authorizes recording via RX,
     * except in passthrough mode where G2 advances pages from singleTapCount.
     */
    internal fun handleSingleTap() {
        val command = singleTapRecordingCommand(
            passthroughEnabled = BleConnectionService.readingPassthroughEnabled.value,
            state = BleConnectionService.voiceState.value,
        )
        if (command == null) {
            Log.i(TAG, "Single tap: no recording command (passthrough or ignored state)")
            return
        }
        Log.i(
            TAG,
            "Single tap: host-authorized recording " +
                if (command == BLE_RX_START_RECORDING) "start" else "stop",
        )
        BleConnectionService.sendCommand(command)
    }

    internal fun handleDoubleTap() {
        val voiceState = BleConnectionService.voiceState.value
        if (shouldInterruptOnDoubleTap(voiceState)) {
            interruptHarnessPipeline(voiceState)
            return
        }
        when (
            passthroughDoubleTapAction(
                passthroughEnabled = BleConnectionService.readingPassthroughEnabled.value,
                state = voiceState,
            )
        ) {
            PassthroughDoubleTapAction.DISABLE -> {
                BleConnectionService.setReadingPassthroughEnabled(appContext, false)
                BleConnectionService.setResponse("パススルーモードを終了しました")
                BleConnectionService.setErrorMessage("")
                Log.i(TAG, "Double tap: passthrough disabled")
            }
            PassthroughDoubleTapAction.ENABLE -> {
                BleConnectionService.setReadingPassthroughEnabled(appContext, true)
                scope.launch(Dispatchers.IO) {
                    startReadingPassthroughFromCurrentScreen(
                        command = "ダブルタップでパススルー開始",
                        silentFailure = false,
                    )
                }
                Log.i(TAG, "Double tap: passthrough enabled")
            }
            PassthroughDoubleTapAction.NONE -> {
                Log.d(TAG, "Double tap ignored")
            }
        }
    }

    /**
     * Accessibility saw Kindle move to the foreground while passthrough is armed.
     * Starts extraction without a harness-node gesture. Failures stay silent.
     */
    internal fun onKindleBecameForeground() {
        if (!BleConnectionService.readingPassthroughEnabled.value) return
        val voiceState = BleConnectionService.voiceState.value
        if (voiceState != VoiceState.READY && voiceState != VoiceState.ERROR) {
            Log.d(TAG, "Auto passthrough skipped: voiceState=$voiceState")
            return
        }
        val snapshot = EvenG2ReadingSession.snapshot(BleConnectionService.doubleTapStatus.value.count)
        if (snapshot.active && snapshot.mode == EvenG2DisplayMode.READING) {
            Log.d(TAG, "Auto passthrough skipped: reading session already active")
            return
        }
        scope.launch(Dispatchers.IO) {
            // Let the Kindle reader settle before headless assist capture.
            kotlinx.coroutines.delay(700L)
            if (!BleConnectionService.readingPassthroughEnabled.value) return@launch
            if (BleConnectionService.voiceState.value != VoiceState.READY &&
                BleConnectionService.voiceState.value != VoiceState.ERROR
            ) {
                return@launch
            }
            if (!KindlePageTurnController.isKindlePackage(KindlePageTurnController.foregroundPackage())) {
                Log.d(TAG, "Auto passthrough skipped: Kindle no longer foreground")
                return@launch
            }
            startReadingPassthroughFromCurrentScreen(
                command = "Kindle前面で自動パススルー",
                silentFailure = true,
                successLabel = "読書パススルーを開始しました（G2・自動）",
            )
        }
    }

    private fun interruptHarnessPipeline(interruptedState: VoiceState) {
        harnessPipelineInterrupted.set(true)
        harnessPipelineJob?.cancel()
        harnessPipelineJob = null
        cancelAssistantRequest(activeAssistantRequestId)
        synchronized(reminderMutationLock) {
            activeReminderId.getAndSet(null)?.let(::rollbackInterruptedReminder)
        }
        val message = "中断しました"
        saveHistoryEntry(
            transcription = BleConnectionService.transcription.value,
            response = BleConnectionService.response.value,
            isSilent = false,
            errorMessage = message,
        )
        BleConnectionService.setResponse("")
        BleConnectionService.setErrorMessage(message)
        BleConnectionService.setVoiceState(VoiceState.READY)
        Log.i(TAG, "Interrupted $interruptedState by double tap")
    }

    private fun rollbackInterruptedReminder(reminderId: String) {
        ReminderAlarmScheduler.cancel(appContext, reminderId)
        reminderRepository.deleteEntry(reminderId)
    }

    private suspend fun startReadingPassthroughFromCurrentScreen(
        command: String,
        silentFailure: Boolean,
        successLabel: String = "読書パススルーを開始しました（G2）",
    ) {
        if (!readingInitialCaptureInFlight.compareAndSet(false, true)) {
            Log.d(TAG, "Reading capture ignored: already in flight")
            return
        }
        try {
            if (!silentFailure) {
                BleConnectionService.setResponse("")
                BleConnectionService.setErrorMessage("")
            }
            BleConnectionService.setVoiceState(VoiceState.RESPONDING)
            val screenContext = runCatching { HeadlessScreenCapture.capture(appContext) }
                .onFailure { Log.w(TAG, "Reading screen capture failed", it) }
                .getOrNull()
            if (!BleConnectionService.readingPassthroughEnabled.value) {
                BleConnectionService.setVoiceState(VoiceState.READY)
                return
            }
            if (screenContext?.isEmpty != false) {
                val message = "Kindle画面の本文を取得できませんでした"
                if (!silentFailure) {
                    BleConnectionService.setResponse(message)
                    BleConnectionService.setErrorMessage(message)
                } else {
                    Log.w(TAG, "Auto passthrough capture empty")
                }
                BleConnectionService.setVoiceState(VoiceState.READY)
                return
            }
            val sourcePackage = screenContext.sourcePackage
            if (sourcePackage != null && !KindlePageTurnController.isKindlePackage(sourcePackage)) {
                val message = "Kindleを表示した状態で操作してください"
                if (!silentFailure) {
                    BleConnectionService.setResponse(message)
                    BleConnectionService.setErrorMessage(message)
                }
                BleConnectionService.setVoiceState(VoiceState.READY)
                Log.w(TAG, "Reading capture rejected: package=$sourcePackage")
                return
            }
            extractAndPresentReadingPassthrough(
                command = command,
                screenContext = screenContext,
                silentFailure = silentFailure,
                successLabel = successLabel,
            )
        } catch (error: Exception) {
            Log.e(TAG, "Reading passthrough failed", error)
            if (!silentFailure) {
                val message = "パススルーエラー: ${error.message}"
                BleConnectionService.setResponse(message)
                BleConnectionService.setErrorMessage(message)
                BleConnectionService.setVoiceState(VoiceState.ERROR)
            } else {
                BleConnectionService.setVoiceState(VoiceState.READY)
            }
        } finally {
            readingInitialCaptureInFlight.set(false)
        }
    }

    internal fun requestEvenG2PageAdvance(expectedRevision: Long) {
        scope.launch(Dispatchers.IO) {
            val completed = runCatching {
                if (EvenG2ReadingSession.snapshot(BleConnectionService.doubleTapStatus.value.count).revision != expectedRevision) {
                    false
                } else {
                    advanceKindlePageIfPossible()
                }
            }.getOrDefault(false)
            if (!completed) {
                EvenG2ReadingSession.failAdvance("Kindleの次ページを取得できませんでした")
            }
        }
    }

    private suspend fun advanceKindlePageIfPossible(): Boolean {
        if (!readingPageTurnInFlight.compareAndSet(false, true)) return false
        try {
            if (!KindlePageTurnController.isAvailable()) {
                showKindlePageTurnError("Accessibility Serviceを有効にしてください")
                return false
            }
            val previous = readingSourceContext?.let(ScreenContextFingerprint::from)
                ?: run {
                    showKindlePageTurnError("現在のKindle画面を確認できません")
                    return false
                }

            var changedScreen: ScreenContext? = null
            val semanticResult = withContext(Dispatchers.Main.immediate) {
                KindlePageTurnController.performSemanticNext()
            }
            if (semanticResult == KindlePageTurnResult.DISPATCHED) {
                changedScreen = captureChangedKindleScreen(previous)
            }
            if (changedScreen == null) {
                val candidates = pageTurnSwipeCandidates(readingPageTurnGesture)
                Log.d(
                    TAG,
                    "Kindle page turn swipe candidates=$candidates " +
                        "preferred=$readingPageTurnGesture",
                )
                for (gesture in candidates) {
                    val swipeResult = KindlePageTurnController.performSwipe(gesture)
                    if (swipeResult != KindlePageTurnResult.DISPATCHED) continue
                    changedScreen = captureChangedKindleScreen(previous)
                    if (changedScreen != null) {
                        readingPageTurnGesture = gesture
                        break
                    }
                }
            }
            if (changedScreen == null) {
                showKindlePageTurnError(
                    "Kindleの次ページ操作または画面更新を確認できませんでした"
                )
                return false
            }

            val result = assistantGateway.submit(
                AssistantRequest(
                    text = ReadingPassthrough.extractionPrompt("Kindleの次ページを表示"),
                    origin = QueryOrigin.HARNESS_NODE_VOICE,
                    conversationId = HARNESS_CONVERSATION_ID,
                    speakResponse = false,
                    languageCode = null,
                    screenContext = changedScreen,
                )
            )
            assistantGateway.resetConversation(HARNESS_CONVERSATION_ID)
            result.onSuccess { reply ->
                presentReadingPassthrough(
                    command = "Kindleの次ページ",
                    extracted = reply.text,
                    sourceContext = changedScreen,
                    saveHistory = false,
                )
            }.onFailure { error ->
                showKindlePageTurnError("次ページ本文の抽出に失敗しました: ${error.message}")
            }
            return result.isSuccess
        } finally {
            readingPageTurnInFlight.set(false)
        }
    }

    private suspend fun captureChangedKindleScreen(
        previous: ScreenContextFingerprint,
    ): ScreenContext? {
        repeat(KINDLE_CAPTURE_ATTEMPTS) { attempt ->
            kotlinx.coroutines.delay(KINDLE_CAPTURE_DELAY_MS * (attempt + 1))
            val captured = runCatching { HeadlessScreenCapture.capture(appContext) }.getOrNull()
                ?: return@repeat
            if (captured.sourcePackage != null &&
                !KindlePageTurnController.isKindlePackage(captured.sourcePackage)
            ) return@repeat
            if (ScreenContextFingerprint.from(captured).changedFrom(previous)) return captured
        }
        return null
    }

    private fun showKindlePageTurnError(message: String) {
        EvenG2ReadingSession.failAdvance(message)
        BleConnectionService.setErrorMessage(message)
        Log.w(TAG, message)
    }

    private fun speakResponse(text: String, requestId: String? = null) {
        if (isAssistantCancelled(requestId)) {
            BleConnectionService.releaseAssistantProcessing()
            return
        }
        BleConnectionService.setVoiceState(VoiceState.SPEAKING)
        if (ttsReady && text.isNotBlank()) {
            BleConnectionService.setPhonePlaybackActive(true)
            val utterancePrefix = "response_${System.currentTimeMillis()}"
            val chunks = TtsTextFormatter.toSpeakableChunks(
                text = text,
                maxLength = TextToSpeech.getMaxSpeechInputLength()
            )
            if (chunks.isEmpty()) {
                BleConnectionService.setVoiceState(VoiceState.READY)
                BleConnectionService.releaseAssistantProcessing()
                com.g150446.voiceharness.assistant.AssistantSessionController.onSpeakingFinished(requestId)
                return
            }
            val finalUtteranceId = "${utterancePrefix}_${chunks.lastIndex}"
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == finalUtteranceId &&
                        BleConnectionService.voiceState.value == VoiceState.SPEAKING
                    ) {
                        BleConnectionService.setVoiceState(VoiceState.READY)
                        BleConnectionService.setPhonePlaybackActive(false)
                        BleConnectionService.releaseAssistantProcessing()
                        com.g150446.voiceharness.assistant.AssistantSessionController
                            .onSpeakingFinished(requestId)
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (BleConnectionService.voiceState.value == VoiceState.SPEAKING) {
                        BleConnectionService.setVoiceState(VoiceState.READY)
                        BleConnectionService.setPhonePlaybackActive(false)
                        BleConnectionService.releaseAssistantProcessing()
                        com.g150446.voiceharness.assistant.AssistantSessionController
                            .onSpeakingFinished(requestId)
                    }
                }
            })
            if (!speakWithFallbacks(chunks, utterancePrefix, responseLanguageCode)) {
                Log.e(TAG, "Unable to speak response for language=${responseLanguageCode ?: "default"}")
                BleConnectionService.setErrorMessage("音声の読み上げに失敗しました")
                BleConnectionService.setVoiceState(VoiceState.READY)
                BleConnectionService.setPhonePlaybackActive(false)
                BleConnectionService.releaseAssistantProcessing()
                com.g150446.voiceharness.assistant.AssistantSessionController
                    .onSpeakingFinished(requestId)
            }
        } else {
            BleConnectionService.setVoiceState(VoiceState.READY)
            BleConnectionService.setPhonePlaybackActive(false)
            BleConnectionService.releaseAssistantProcessing()
            com.g150446.voiceharness.assistant.AssistantSessionController.onSpeakingFinished(requestId)
        }
    }

    fun stopSpeaking() {
        tts?.stop()
        BleConnectionService.setPhonePlaybackActive(false)
        BleConnectionService.setVoiceState(VoiceState.READY)
    }

    private fun commitPipelineTiming() {
        val elapsed = pipelineTiming.commit(SystemClock.elapsedRealtime()) ?: return
        BleConnectionService.setLastPipelineMs(elapsed)
        Log.d(TAG, "Pipeline timing: $elapsed ms")
    }

    fun switchProfile(profile: OnDeviceProfile) {
        aiFacade.switchProfile(profile)
        tts?.stop()
        EvenG2ReadingSession.clearDisplay()
        readingSourceContext = null
        readingPageTurnGesture = PageTurnGesture.UNKNOWN
        if (BleConnectionService.voiceState.value != VoiceState.RECORDING) {
            BleConnectionService.setVoiceState(VoiceState.READY)
        }
        Log.d(TAG, "Switched on-device profile to $profile")
        scope.launch(Dispatchers.IO) {
            aiBackend.ensureReady()
                .onFailure { Log.w(TAG, "Profile warm-up failed for $profile: ${it.message}") }
        }
    }

    fun switchSttBackend(backend: SttBackendId) {
        aiFacade.switchSttBackend(backend)
        Log.d(TAG, "Switched STT backend to $backend")
        scope.launch(Dispatchers.IO) {
            aiBackend.ensureReady()
                .onFailure { Log.w(TAG, "STT warm-up failed for $backend: ${it.message}") }
        }
    }

    fun switchLlmBackend(backend: LlmBackendId) {
        aiFacade.switchLlmBackend(backend)
        Log.d(TAG, "Switched LLM backend to $backend")
        scope.launch(Dispatchers.IO) {
            aiBackend.ensureReady()
                .onFailure { Log.w(TAG, "LLM warm-up failed for $backend: ${it.message}") }
        }
    }

    fun disconnect() {
        tts?.stop()
        BleConnectionService.setPhonePlaybackActive(false)
        EvenG2ReadingSession.clearDisplay()
        readingSourceContext = null
        readingPageTurnGesture = PageTurnGesture.UNKNOWN
        isCollectingPcm = false
        pcmBuffer.reset()
        sileroVad?.reset()
        BleConnectionService.setBleMode(false)
        val currentState = BleConnectionService.voiceState.value
        if (currentState == VoiceState.RECORDING || currentState == VoiceState.SPEAKING) {
            BleConnectionService.setVoiceState(VoiceState.READY)
        }
    }

    fun shutdown() {
        tts?.stop()
        BleConnectionService.setPhonePlaybackActive(false)
        tts?.shutdown()
        recordingCuePlayer.release()
        sileroVad?.close()
        aiFacade.release()
    }

    // --- Helpers ---

    private fun applyTtsLanguage(languageCode: String?): Locale? {
        val candidateLocales = SpeechLanguageResolver.candidateLocales(languageCode, Locale.getDefault())
        for (locale in candidateLocales) {
            if (trySetTtsLocale(locale)) {
                return locale
            }
        }
        Log.w(TAG, "No supported TTS locale found for language=${languageCode ?: "default"}")
        return null
    }

    private fun trySetTtsLocale(locale: Locale): Boolean {
        val textToSpeech = tts ?: return false
        val result = textToSpeech.setLanguage(locale)
        if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
            return true
        }
        Log.w(TAG, "TTS locale unavailable: ${locale.toLanguageTag()} result=$result")
        return false
    }

    private fun speakWithFallbacks(chunks: List<String>, utterancePrefix: String, languageCode: String?): Boolean {
        val textToSpeech = tts ?: return false
        val candidateLocales = SpeechLanguageResolver.candidateLocales(languageCode, Locale.getDefault())

        for (locale in candidateLocales) {
            if (!trySetTtsLocale(locale)) continue
            if (queueSpeechChunks(textToSpeech, chunks, utterancePrefix, locale.toLanguageTag())) {
                return true
            }
        }

        return queueSpeechChunks(textToSpeech, chunks, utterancePrefix, "current")
    }

    private fun queueSpeechChunks(
        textToSpeech: TextToSpeech,
        chunks: List<String>,
        utterancePrefix: String,
        localeLabel: String
    ): Boolean {
        textToSpeech.stop()

        for ((index, chunk) in chunks.withIndex()) {
            val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val utteranceId = "${utterancePrefix}_$index"
            val result = textToSpeech.speak(chunk, queueMode, null, utteranceId)
            Log.d(
                TAG,
                "TTS speak attempt locale=$localeLabel chunk=${index + 1}/${chunks.size} length=${chunk.length} result=$result"
            )
            if (result != TextToSpeech.SUCCESS) {
                textToSpeech.stop()
                return false
            }
        }

        return true
    }

}

private const val HARNESS_CONVERSATION_ID = "harness-node"
