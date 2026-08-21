package com.g150446.voiceharness

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
private const val PCM_CHANNELS = 1
private const val PCM_BITS_PER_SAMPLE = 16

/**
 * Owns the audio processing pipeline: PCM buffering → VAD → on-device STT/LLM → TTS.
 * Profile (Gemma default / Qwen) is selected via Model Settings.
 */
internal class VoiceProcessor(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val smartGlassesOutput: SmartGlassesOutputManager
) : TextToSpeech.OnInitListener {

    private val historyRepository = HistoryRepository(appContext)
    private val reminderRepository = ReminderRepository(appContext)
    private val conversationSession = ConversationSession()
    private val aiFacade = OnDeviceAiFacade(appContext)
    private val aiBackend: VoiceAiBackend get() = aiFacade

    private val pcmBuffer = ByteArrayOutputStream()
    private var isCollectingPcm = false
    private var recordingStartedAtElapsedMs = 0L
    private val pipelineTiming = PipelineTimingTracker()
    private val silenceEndpoint = SilenceEndpointTracker()
    private val streamingFramePcm = ByteArray(SileroVad.FRAME_SIZE * 2)
    private var streamingFrameOffset = 0

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
                    feedStreamingVad(input.packet.pcmData)
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
        // Prefer an already-idle Z100 (auto-release after the read window). When still
        // displaying, stopDisplay() clears it; when idle it skips Z100 BLE traffic.
        smartGlassesOutput.stopDisplay()
        pcmBuffer.reset()
        resetStreamingVad()
        recordingStartedAtElapsedMs = SystemClock.elapsedRealtime()
        isCollectingPcm = true
        BleConnectionService.setBleMode(true)
        BleConnectionService.setTranscription("")
        BleConnectionService.setResponse("")
        BleConnectionService.setErrorMessage("")
        if (BleConnectionService.voiceState.value == VoiceState.SPEAKING) tts?.stop()
        BleConnectionService.setVoiceState(VoiceState.RECORDING)
        recordingCuePlayer.playStarted()
        Log.d(
            TAG,
            "BLE recording started (firmware-initiated), glasses=${smartGlassesOutput.state.value}"
        )
    }

    private fun handleBleRecordingStopped(reason: String = "firmware") {
        if (BleConnectionService.voiceState.value != VoiceState.RECORDING ||
            !BleConnectionService.bleMode.value
        ) return
        isCollectingPcm = false
        resetStreamingVad()
        val recordingDurationMs = (SystemClock.elapsedRealtime() - recordingStartedAtElapsedMs)
            .coerceAtLeast(0L)
        recordingStartedAtElapsedMs = 0L
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
            conversationSession.reset()
            historyRepository.addEntry(HistoryEntry(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                transcription = "",
                response = "",
                isSilent = true,
                errorMessage = "音声データの受信が不完全でした"
            ))
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
            conversationSession.reset()
            historyRepository.addEntry(HistoryEntry(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                transcription = "",
                response = "",
                isSilent = true,
                errorMessage = ""
            ))
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

        scope.launch(Dispatchers.IO) {
            val trimmedPcm = PcmSilenceTrimmer.trim(pcmData, PCM_SAMPLE_RATE)
            if (trimmedPcm !== pcmData) {
                val beforeMs = pcmData.size * 1_000L / (PCM_SAMPLE_RATE * PCM_CHANNELS * 2)
                val afterMs = trimmedPcm.size * 1_000L / (PCM_SAMPLE_RATE * PCM_CHANNELS * 2)
                Log.d(TAG, "Trimmed BLE PCM from ${beforeMs}ms to ${afterMs}ms")
            }
            val wavFile = buildWavFile(trimmedPcm) ?: run {
                pipelineTiming.discard()
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
                historyRepository.addEntry(HistoryEntry(
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    transcription = "",
                    response = "",
                    isSilent = false,
                    errorMessage = errMsg
                ))
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
                historyRepository.addEntry(HistoryEntry(
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    transcription = "",
                    response = "",
                    isSilent = false,
                    errorMessage = errMsg
                ))
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
                historyRepository.addEntry(HistoryEntry(
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    transcription = "",
                    response = "",
                    isSilent = true,
                    errorMessage = ""
                ))
                BleConnectionService.setTranscription("")
                BleConnectionService.setResponse("")
                BleConnectionService.setErrorMessage("発話として認識できませんでした（背景音の可能性）")
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

            if (conversationSession.isExpired()) {
                conversationSession.reset()
            }

            val resetParse = ConversationResetDetector.parse(transcribed)
            if (resetParse.shouldReset) {
                conversationSession.reset()
                Log.d(TAG, "Conversation context reset by voice command")
                val remaining = resetParse.remainingUserText?.trim().orEmpty()
                if (remaining.isEmpty()) {
                    val confirmation = ConversationResetDetector.confirmationMessage(
                        languageCode = responseLanguageCode,
                        remainingUserText = null
                    )
                    BleConnectionService.setResponse(confirmation)
                    historyRepository.addEntry(
                        HistoryEntry(
                            id = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            transcription = transcribed,
                            response = confirmation,
                            isSilent = false,
                            errorMessage = ""
                        )
                    )
                    presentResponse(confirmation)
                    return
                }
                BleConnectionService.setTranscription(remaining)
                conversationSession.addTurn("user", remaining)
            } else {
                conversationSession.addTurn("user", transcribed)
            }

            val chat = aiBackend.chat(
                conversationHistory = conversationSession.turnsForInference(),
                languageCode = responseLanguageCode
            )
            if (chat.isFailure) {
                val errMsg = "Chat error: ${chat.exceptionOrNull()?.message}"
                BleConnectionService.setErrorMessage(errMsg)
                BleConnectionService.setVoiceState(VoiceState.ERROR)
                historyRepository.addEntry(HistoryEntry(
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    transcription = BleConnectionService.transcription.value,
                    response = "",
                    isSilent = false,
                    errorMessage = errMsg
                ))
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
                val finalResponse = responseText.ifBlank { "(返答なし)" }
                BleConnectionService.setResponse(finalResponse)
                Log.d(TAG, "Response: $responseText")
                conversationSession.addTurn("assistant", finalResponse)
                historyRepository.addEntry(HistoryEntry(
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    transcription = BleConnectionService.transcription.value,
                    response = finalResponse,
                    isSilent = false,
                    errorMessage = ""
                ))
                presentResponse(finalResponse)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during on-device transcribe/respond", e)
            val errMsg = "エラー: ${e.message}"
            BleConnectionService.setErrorMessage(errMsg)
            BleConnectionService.setVoiceState(VoiceState.ERROR)
            historyRepository.addEntry(HistoryEntry(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                transcription = BleConnectionService.transcription.value,
                response = "",
                isSilent = false,
                errorMessage = errMsg
            ))
        } finally {
            pipelineTiming.discardIfRunning()
            try { file.delete() } catch (_: Exception) {}
        }
    }

    // --- VAD helpers ---

    private fun resetStreamingVad() {
        silenceEndpoint.reset()
        streamingFrameOffset = 0
        sileroVad?.reset()
    }

    private fun feedStreamingVad(pcmData: ByteArray) {
        if (!isCollectingPcm || pcmData.isEmpty()) return
        var offset = 0
        while (offset < pcmData.size) {
            val copy = minOf(streamingFramePcm.size - streamingFrameOffset, pcmData.size - offset)
            System.arraycopy(pcmData, offset, streamingFramePcm, streamingFrameOffset, copy)
            streamingFrameOffset += copy
            offset += copy
            if (streamingFrameOffset < streamingFramePcm.size) return
            streamingFrameOffset = 0
            val isSpeech = try {
                frameLooksLikeSpeech(streamingFramePcm)
            } catch (e: Exception) {
                Log.w(TAG, "Streaming VAD frame failed — treating as silence", e)
                false
            }
            if (silenceEndpoint.onFrame(isSpeech)) {
                Log.d(TAG, "Silence endpoint: ${BLE_SILENCE_STOP_MS}ms silence, stopping recording")
                BleConnectionService.sendCommand(BLE_RX_STOP_RECORDING)
                handleBleRecordingStopped("silence-timeout")
                return
            }
        }
    }

    private fun frameLooksLikeSpeech(pcmFrame: ByteArray): Boolean {
        val analysis = BleSpeechDetector.analyzeBlePcm(pcmFrame)
        val vad = sileroVad ?: return analysis.rmsAfterDc >= BLE_ENERGY_RESCUE_RMS_THRESHOLD &&
            analysis.peakAfterDc >= BLE_ENERGY_RESCUE_PEAK_THRESHOLD
        val samples = FloatArray(analysis.samples.size) { i ->
            analysis.samples[i] * analysis.gain
        }
        return vad.predict(samples) > SILERO_SPEECH_THRESHOLD
    }

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
            conversationSession.addTurn("assistant", errorMsg)
            historyRepository.addEntry(HistoryEntry(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                transcription = BleConnectionService.transcription.value,
                response = errorMsg,
                isSilent = false,
                errorMessage = ""
            ))
            presentResponse(errorMsg)
            return
        }

        val scheduledAtMillis = parseIso8601ToMillis(datetimeStr)
        if (scheduledAtMillis == null) {
            val errorMsg = "日時の解析に失敗しました: $datetimeStr"
            BleConnectionService.setResponse(errorMsg)
            conversationSession.addTurn("assistant", errorMsg)
            historyRepository.addEntry(HistoryEntry(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                transcription = BleConnectionService.transcription.value,
                response = errorMsg,
                isSilent = false,
                errorMessage = ""
            ))
            presentResponse(errorMsg)
            return
        }

        val entry = ReminderEntry(
            title = title,
            scheduledAtMillis = scheduledAtMillis,
            isTtsEnabled = ttsEnabled
        )
        reminderRepository.addEntry(entry)
        ReminderAlarmScheduler.schedule(appContext, entry)

        val confirmation = buildReminderConfirmationText(title, datetimeStr, ttsEnabled)
        BleConnectionService.setResponse(confirmation)
        conversationSession.addTurn("assistant", confirmation)
        historyRepository.addEntry(HistoryEntry(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            transcription = BleConnectionService.transcription.value,
            response = confirmation,
            isSilent = false,
            errorMessage = ""
        ))
        conversationSession.reset()
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

    private suspend fun presentResponse(text: String) {
        commitPipelineTiming()
        val target = BleConnectionService.responseOutputTarget.value
        val glassesResult = if (target == ResponseOutputTarget.SMART_GLASSES) {
            smartGlassesOutput.displayResponse(text)
        } else {
            null
        }
        val decision = decideResponseDelivery(target, glassesResult)
        if (decision.useSmartGlasses) {
            tts?.stop()
            BleConnectionService.setVoiceState(VoiceState.READY)
            Log.d(TAG, "Response routed to Z100")
            return
        }
        decision.fallbackMessage?.let {
            BleConnectionService.setErrorMessage(it)
            val failure = glassesResult as? SmartGlassesDisplayResult.Failed
            Log.w(TAG, "$it: ${failure?.message}", failure?.cause)
        }
        speakResponse(text)
    }

    private fun speakResponse(text: String) {
        BleConnectionService.setVoiceState(VoiceState.SPEAKING)
        if (ttsReady && text.isNotBlank()) {
            val utterancePrefix = "response_${System.currentTimeMillis()}"
            val chunks = TtsTextFormatter.toSpeakableChunks(
                text = text,
                maxLength = TextToSpeech.getMaxSpeechInputLength()
            )
            if (chunks.isEmpty()) {
                BleConnectionService.setVoiceState(VoiceState.READY)
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
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (BleConnectionService.voiceState.value == VoiceState.SPEAKING) {
                        BleConnectionService.setVoiceState(VoiceState.READY)
                    }
                }
            })
            if (!speakWithFallbacks(chunks, utterancePrefix, responseLanguageCode)) {
                Log.e(TAG, "Unable to speak response for language=${responseLanguageCode ?: "default"}")
                BleConnectionService.setErrorMessage("音声の読み上げに失敗しました")
                BleConnectionService.setVoiceState(VoiceState.READY)
            }
        } else {
            BleConnectionService.setVoiceState(VoiceState.READY)
        }
    }

    fun stopSpeaking() {
        tts?.stop()
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
        smartGlassesOutput.stopDisplay()
        if (BleConnectionService.voiceState.value != VoiceState.RECORDING) {
            BleConnectionService.setVoiceState(VoiceState.READY)
        }
        Log.d(TAG, "Switched on-device profile to $profile")
        scope.launch(Dispatchers.IO) {
            aiBackend.ensureReady()
                .onFailure { Log.w(TAG, "Profile warm-up failed for $profile: ${it.message}") }
        }
    }

    fun disconnect() {
        tts?.stop()
        smartGlassesOutput.stopDisplay()
        isCollectingPcm = false
        pcmBuffer.reset()
        resetStreamingVad()
        BleConnectionService.setBleMode(false)
        val currentState = BleConnectionService.voiceState.value
        if (currentState == VoiceState.RECORDING || currentState == VoiceState.SPEAKING) {
            BleConnectionService.setVoiceState(VoiceState.READY)
        }
    }

    fun shutdown() {
        tts?.stop()
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
