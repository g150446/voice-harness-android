package com.g150446.voiceharness

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
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
 * Owns the audio processing pipeline: PCM buffering → VAD → Groq Whisper → Groq Chat → TTS.
 *
 * Runs entirely within the provided [scope] (the service's serviceScope), so it survives
 * Activity destruction and processes audio even when the phone screen is off.
 */
class VoiceProcessor(
    private val appContext: Context,
    private val scope: CoroutineScope
) : TextToSpeech.OnInitListener {

    private val historyRepository = HistoryRepository(appContext)
    private val reminderRepository = ReminderRepository(appContext)
    private val conversationSession = ConversationSession()

    private val pcmBuffer = ByteArrayOutputStream()
    private var isCollectingPcm = false

    private val sileroVad: SileroVad? = try {
        SileroVad(appContext)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to load Silero VAD — VAD disabled", e)
        null
    }

    private val httpClient = OkHttpClient()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var responseLanguageCode: String? = null

    init {
        tts = TextToSpeech(appContext, this)

        scope.launch {
            BleConnectionService.bleEvents.collect { event ->
                when (event) {
                    is BleEvent.RecordingStarted -> handleBleRecordingStarted()
                    is BleEvent.RecordingStopped -> handleBleRecordingStopped()
                    else -> {}
                }
            }
        }

        scope.launch {
            BleConnectionService.audioPackets.collect { packet ->
                if (isCollectingPcm) {
                    pcmBuffer.write(packet.pcmData)
                }
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
        pcmBuffer.reset()
        isCollectingPcm = true
        BleConnectionService.setBleMode(true)
        BleConnectionService.setTranscription("")
        BleConnectionService.setResponse("")
        BleConnectionService.setErrorMessage("")
        if (BleConnectionService.voiceState.value == VoiceState.SPEAKING) tts?.stop()
        BleConnectionService.setVoiceState(VoiceState.RECORDING)
        Log.d(TAG, "BLE recording started (firmware-initiated)")
    }

    private fun handleBleRecordingStopped() {
        if (BleConnectionService.voiceState.value != VoiceState.RECORDING ||
            !BleConnectionService.bleMode.value
        ) return
        isCollectingPcm = false
        val pcmData = pcmBuffer.toByteArray()
        pcmBuffer.reset()
        BleConnectionService.setBleMode(false)
        Log.d(TAG, "BLE recording stopped by firmware, ${pcmData.size} bytes of PCM")

        tts?.stop()

        if (!hasSpeechInPcm(pcmData)) {
            Log.d(TAG, "VAD: no speech in BLE audio — skipping Groq")
            conversationSession.reset()
            historyRepository.addEntry(HistoryEntry(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                transcription = "",
                response = "",
                isSilent = true,
                errorMessage = ""
            ))
            BleConnectionService.setVoiceState(VoiceState.READY)
            return
        }

        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            BleConnectionService.setErrorMessage("Groq API キーが未設定です。設定画面で入力してください。")
            BleConnectionService.setVoiceState(VoiceState.ERROR)
            return
        }

        scope.launch(Dispatchers.IO) {
            val wavFile = buildWavFile(pcmData) ?: run {
                BleConnectionService.setErrorMessage("WAV ファイルの作成に失敗しました")
                BleConnectionService.setVoiceState(VoiceState.ERROR)
                return@launch
            }
            transcribeAndRespondFromFile(wavFile, apiKey, "audio/wav")
        }
    }

    // --- Shared transcription + chat logic ---

    private suspend fun transcribeAndRespondFromFile(file: File, apiKey: String, mimeType: String) {
        BleConnectionService.setVoiceState(VoiceState.TRANSCRIBING)
        BleConnectionService.setErrorMessage("")
        responseLanguageCode = null

        try {
            val transcriptionBody = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", file.name, file.asRequestBody(mimeType.toMediaType()))
                .addFormDataPart("model", "whisper-large-v3-turbo")
                .addFormDataPart("response_format", "json")
                .build()

            val transcriptionResponse = httpClient.newCall(
                Request.Builder()
                    .url("https://api.groq.com/openai/v1/audio/transcriptions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(transcriptionBody)
                    .build()
            ).execute()
            val transcriptionBodyText = transcriptionResponse.body?.string() ?: ""

            if (!transcriptionResponse.isSuccessful) {
                val errMsg = "Whisper error ${transcriptionResponse.code}: ${transcriptionBodyText.take(200)}"
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

            val transcriptionPayload = parseTranscriptionPayload(transcriptionBodyText)
            val rawText = transcriptionPayload.text

            if (rawText.isBlank() || isWhisperHallucination(rawText)) {
                Log.w(TAG, "Whisper hallucination detected: '$rawText' — treating as silent")
                historyRepository.addEntry(HistoryEntry(
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    transcription = "",
                    response = "",
                    isSilent = true,
                    errorMessage = ""
                ))
                BleConnectionService.setVoiceState(VoiceState.READY)
                return
            }

            val transcribed = rawText.ifBlank { "(音声なし)" }
            responseLanguageCode = SpeechLanguageResolver.resolvePreferredLanguageCode(
                whisperLanguageCode = transcriptionPayload.languageCode,
                transcribedText = transcribed
            )
            BleConnectionService.setTranscription(transcribed)
            Log.d(TAG, "Transcription: $transcribed, language=${responseLanguageCode ?: "default"}")

            BleConnectionService.setVoiceState(VoiceState.RESPONDING)

            if (conversationSession.isExpired()) {
                conversationSession.reset()
            }
            conversationSession.addTurn("user", transcribed)

            val chatJson = GroqChatRequestBuilder.buildRequestBodyWithFunctionCalling(
                conversationHistory = conversationSession.turns,
                languageCode = responseLanguageCode
            )

            val chatResponse = httpClient.newCall(
                Request.Builder()
                    .url("https://api.groq.com/openai/v1/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(chatJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            ).execute()
            val chatBodyText = chatResponse.body?.string().orEmpty()

            if (!chatResponse.isSuccessful) {
                val errMsg = "Chat error ${chatResponse.code}: ${chatBodyText.take(200)}"
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

            val choices = JSONObject(chatBodyText).optJSONArray("choices")
            val messageObj = if (choices != null && choices.length() > 0) {
                choices.getJSONObject(0).optJSONObject("message")
            } else null

            if (messageObj != null && messageObj.has("tool_calls")) {
                handleToolCalls(messageObj, apiKey)
            } else {
                val responseText = messageObj?.optString("content").orEmpty()
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
                speakResponse(responseText)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error during transcribe/respond", e)
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
            try { file.delete() } catch (_: Exception) {}
        }
    }

    // --- VAD helpers ---

    private fun hasSpeechInPcm(pcmData: ByteArray): Boolean {
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
            "Silero VAD did not accept BLE audio (reason=${sileroDecision.spectrumReason}, maxProb=${"%.3f".format(Locale.US, maxProb)}) — checking spectrum VAD"
        )
        return hasSpeechBySpectrum(
            samples = analysis.samples,
            reason = sileroDecision.spectrumReason ?: "Silero rejected audio",
            peakAfterDc = analysis.peakAfterDc,
            rmsAfterDc = analysis.rmsAfterDc
        )
    }

    private fun hasSpeechBySpectrum(
        samples: FloatArray,
        reason: String,
        peakAfterDc: Float? = null,
        rmsAfterDc: Float? = null
    ): Boolean {
        val result = BleSpeechDetector.detectSpeechBySpectrum(samples, PCM_SAMPLE_RATE)
        val rescued = shouldRescueBleSpectrum(
            peakAfterDc = peakAfterDc,
            rmsAfterDc = rmsAfterDc,
            maxBandRatio = result.maxBandRatio
        )
        Log.d(
            TAG,
            "Spectrum VAD fallback: reason=$reason, speechFrames=${result.speechFrames}/${result.activeFrames} active (${result.totalFrames} total, ${"%.1f".format(Locale.US, result.ratio * 100)}%), maxBandRatio=${"%.3f".format(Locale.US, result.maxBandRatio)}, rescued=$rescued, topBandRatios=${result.topBandRatios.joinToString(prefix = "[", postfix = "]") { "%.3f".format(Locale.US, it) }}"
        )
        if (result.hasSpeech(BleSpeechDetector.SPEECH_FRAME_MIN_RATIO)) {
            return true
        }
        if (rescued) {
            Log.w(
                TAG,
                "Spectrum VAD rescue accepted BLE audio: peakAfterDC=${"%.4f".format(Locale.US, peakAfterDc)}, rmsAfterDC=${"%.4f".format(Locale.US, rmsAfterDc)}, maxBandRatio=${"%.3f".format(Locale.US, result.maxBandRatio)}"
            )
            return true
        }
        return false
    }

    // --- Reminder helpers ---

    private suspend fun handleToolCalls(messageObj: JSONObject, apiKey: String) {
        val toolCalls = messageObj.optJSONArray("tool_calls") ?: return
        val firstToolCall = toolCalls.optJSONObject(0) ?: return
        val functionObj = firstToolCall.optJSONObject("function") ?: return
        val functionName = functionObj.optString("name", "")
        if (functionName != "set_reminder") {
            Log.w(TAG, "Unexpected tool call: $functionName")
            return
        }

        val argumentsStr = functionObj.optString("arguments", "{}")
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
            speakResponse(errorMsg)
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
            speakResponse(errorMsg)
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
        speakResponse(confirmation)
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

    fun disconnect() {
        tts?.stop()
        isCollectingPcm = false
        pcmBuffer.reset()
        BleConnectionService.setBleMode(false)
        val currentState = BleConnectionService.voiceState.value
        if (currentState == VoiceState.RECORDING || currentState == VoiceState.SPEAKING) {
            BleConnectionService.setVoiceState(VoiceState.READY)
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        sileroVad?.close()
    }

    // --- Helpers ---

    private val whisperHallucinationPatterns = setOf(
        "thank you", "thanks", "thank you.", "thanks.",
        "thank you very much", "thank you very much.",
        "you", "bye", "bye."
    )

    private fun isWhisperHallucination(text: String): Boolean =
        text.trim().lowercase() in whisperHallucinationPatterns

    private fun parseTranscriptionPayload(responseBody: String): TranscriptionPayload {
        val trimmedBody = responseBody.trim()
        if (trimmedBody.startsWith("{")) {
            val json = JSONObject(trimmedBody)
            return TranscriptionPayload(
                text = json.optString("text").trim(),
                languageCode = json.optString("language")
            )
        }
        return TranscriptionPayload(
            text = trimmedBody,
            languageCode = null
        )
    }

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

    private fun getApiKey(): String =
        appContext.getSharedPreferences("groq_prefs", Context.MODE_PRIVATE)
            .getString("groq_api_key", "") ?: ""
}

private data class TranscriptionPayload(
    val text: String,
    val languageCode: String?
)
