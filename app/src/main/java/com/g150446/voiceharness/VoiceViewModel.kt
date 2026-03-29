package com.g150446.voiceharness

import android.app.Application
import android.media.MediaRecorder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import java.util.Locale

private const val TAG = "VoiceViewModel"
private const val PCM_SAMPLE_RATE = 16000
private const val PCM_CHANNELS = 1
private const val PCM_BITS_PER_SAMPLE = 16

// VAD thresholds
private const val VAD_AMPLITUDE_THRESHOLD = 500    // MediaRecorder.getMaxAmplitude() range 0-32767
private const val SILERO_SPEECH_THRESHOLD = 0.5f   // Silero per-frame probability threshold
private const val SILERO_FRAME_MIN_RATIO  = 0.05   // fraction of frames that must be speech
private const val SILERO_STUCK_MAX_PROB   = 0.01f  // suspiciously low for every frame => fallback
private const val BLE_RESCUE_PEAK_THRESHOLD = 0.08f
private const val BLE_RESCUE_RMS_THRESHOLD = 0.015f
private const val BLE_RESCUE_BAND_RATIO_THRESHOLD = 0.48

private data class TranscriptionPayload(
    val text: String,
    val languageCode: String?
)

enum class VoiceState {
    READY,
    RECORDING,
    TRANSCRIBING,
    RESPONDING,
    SPEAKING,
    ERROR
}

class VoiceViewModel(application: Application) : AndroidViewModel(application), TextToSpeech.OnInitListener {

    private val _state = MutableStateFlow(VoiceState.READY)
    val state: StateFlow<VoiceState> = _state

    private val _transcription = MutableStateFlow("")
    val transcription: StateFlow<String> = _transcription

    private val _response = MutableStateFlow("")
    val response: StateFlow<String> = _response

    private val _errorMessage = MutableStateFlow("")
    val errorMessage: StateFlow<String> = _errorMessage

    private val _bleConnectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val bleConnectionState: StateFlow<BleConnectionState> = _bleConnectionState

    private val _bleMode = MutableStateFlow(false)
    val bleMode: StateFlow<Boolean> = _bleMode

    // Phone mic recording
    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var maxAmplitudeSeen = 0
    private var amplitudePollingJob: Job? = null

    // BLE PCM accumulation
    private val pcmBuffer = ByteArrayOutputStream()
    private var isCollectingPcm = false

    private val sileroVad: SileroVad? = try {
        SileroVad(application)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to load Silero VAD — VAD disabled", e)
        null
    }

    private val httpClient = OkHttpClient()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var responseLanguageCode: String? = null

    init {
        tts = TextToSpeech(application, this)

        viewModelScope.launch {
            BleConnectionService.connectionState.collect { state ->
                _bleConnectionState.value = state
            }
        }

        viewModelScope.launch {
            BleConnectionService.bleEvents.collect { event ->
                when (event) {
                    is BleEvent.RecordingStarted -> handleBleRecordingStarted()
                    is BleEvent.RecordingStopped -> handleBleRecordingStopped()
                    else -> {}
                }
            }
        }

        viewModelScope.launch {
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
        if (_state.value == VoiceState.RECORDING) return
        pcmBuffer.reset()
        isCollectingPcm = true
        _bleMode.value = true
        _transcription.value = ""
        _response.value = ""
        _errorMessage.value = ""
        if (_state.value == VoiceState.SPEAKING) tts?.stop()
        _state.value = VoiceState.RECORDING
        Log.d(TAG, "BLE recording started (firmware-initiated)")
    }

    private fun handleBleRecordingStopped() {
        if (_state.value != VoiceState.RECORDING || !_bleMode.value) return
        isCollectingPcm = false
        val pcmData = pcmBuffer.toByteArray()
        pcmBuffer.reset()
        _bleMode.value = false
        Log.d(TAG, "BLE recording stopped by firmware, ${pcmData.size} bytes of PCM")

        tts?.stop()

        if (!hasSpeechInPcm(pcmData)) {
            Log.d(TAG, "VAD: no speech in BLE audio — skipping Groq")
            _state.value = VoiceState.READY
            return
        }

        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            _errorMessage.value = "Groq API キーが未設定です。設定画面で入力してください。"
            _state.value = VoiceState.ERROR
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val wavFile = buildWavFile(pcmData) ?: run {
                _errorMessage.value = "WAV ファイルの作成に失敗しました"
                _state.value = VoiceState.ERROR
                return@launch
            }
            transcribeAndRespondFromFile(wavFile, apiKey, "audio/wav")
        }
    }

    // --- Phone mic recording ---

    fun startRecording() {
        if (_state.value != VoiceState.READY && _state.value != VoiceState.ERROR) return
        val ctx = getApplication<Application>()
        val file = File(ctx.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        audioFile = file

        try {
            mediaRecorder = MediaRecorder(ctx).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64_000)
                setAudioSamplingRate(16_000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            _state.value = VoiceState.RECORDING
            _bleMode.value = false
            _transcription.value = ""
            _response.value = ""
            _errorMessage.value = ""
            maxAmplitudeSeen = 0
            // Poll amplitude every 100ms to track peak during recording
            amplitudePollingJob = viewModelScope.launch {
                while (_state.value == VoiceState.RECORDING && !_bleMode.value) {
                    delay(100)
                    val amp = mediaRecorder?.maxAmplitude ?: 0
                    if (amp > maxAmplitudeSeen) maxAmplitudeSeen = amp
                }
            }
            Log.d(TAG, "Phone mic recording started: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            releaseRecorder()
            _errorMessage.value = "録音開始失敗: ${e.message}"
            _state.value = VoiceState.ERROR
        }
    }

    fun stopRecordingAndProcess() {
        if (_state.value != VoiceState.RECORDING || _bleMode.value) return

        amplitudePollingJob?.cancel()
        amplitudePollingJob = null
        val finalMaxAmplitude = maxAmplitudeSeen
        maxAmplitudeSeen = 0

        val file = audioFile ?: return
        try {
            mediaRecorder?.apply { stop(); reset(); release() }
            mediaRecorder = null
            Log.d(TAG, "Phone mic recording stopped, maxAmplitude=$finalMaxAmplitude")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop recording", e)
        }

        // Always stop TTS even if VAD finds no speech
        tts?.stop()

        if (finalMaxAmplitude < VAD_AMPLITUDE_THRESHOLD) {
            Log.d(TAG, "VAD: no speech detected (maxAmplitude=$finalMaxAmplitude) — skipping Groq")
            _state.value = VoiceState.READY
            file.delete()
            return
        }

        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            _errorMessage.value = "Groq API キーが未設定です。設定画面で入力してください。"
            _state.value = VoiceState.ERROR
            file.delete()
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            transcribeAndRespondFromFile(file, apiKey, "audio/mp4")
        }
    }

    // --- Shared transcription + chat logic ---

    private suspend fun transcribeAndRespondFromFile(file: File, apiKey: String, mimeType: String) {
        _state.value = VoiceState.TRANSCRIBING
        _errorMessage.value = ""
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
                _errorMessage.value = "Whisper error ${transcriptionResponse.code}: ${transcriptionBodyText.take(200)}"
                _state.value = VoiceState.ERROR
                return
            }

            val transcriptionPayload = parseTranscriptionPayload(transcriptionBodyText)
            val transcribed = transcriptionPayload.text.ifBlank { "(音声なし)" }
            responseLanguageCode = SpeechLanguageResolver.resolvePreferredLanguageCode(
                whisperLanguageCode = transcriptionPayload.languageCode,
                transcribedText = transcribed
            )
            _transcription.value = transcribed
            Log.d(TAG, "Transcription: $transcribed, language=${responseLanguageCode ?: "default"}")

            _state.value = VoiceState.RESPONDING

            val chatJson = GroqChatRequestBuilder.buildRequestBody(
                userText = transcribed,
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
                _errorMessage.value = "Chat error ${chatResponse.code}: ${chatBodyText.take(200)}"
                _state.value = VoiceState.ERROR
                return
            }

            val choices = JSONObject(chatBodyText).optJSONArray("choices")
            val responseText = if (choices != null && choices.length() > 0) {
                choices.getJSONObject(0).optJSONObject("message")?.optString("content").orEmpty()
            } else "(返答なし)"

            _response.value = responseText.ifBlank { "(返答なし)" }
            Log.d(TAG, "Response: $responseText")

            speakResponse(responseText)

        } catch (e: Exception) {
            Log.e(TAG, "Error during transcribe/respond", e)
            _errorMessage.value = "エラー: ${e.message}"
            _state.value = VoiceState.ERROR
        } finally {
            try { file.delete() } catch (_: Exception) {}
        }
    }

    // --- VAD helpers ---

    /**
     * Silero VAD: classify BLE PCM as speech using the Silero deep-learning model.
     *
     * Processes 512-sample (32 ms) frames sequentially, passing RNN state (h, c)
     * between frames so the model captures speech continuity across the recording.
     * Returns true if ≥5% of frames have speech probability > 0.5.
     *
     * Falls back to FFT-based spectrum VAD if Silero is unavailable or appears stuck.
     */
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

        if (ratio >= SILERO_FRAME_MIN_RATIO) {
            return true
        }

        if (maxProb <= SILERO_STUCK_MAX_PROB) {
            Log.w(TAG, "Silero VAD probabilities look stuck (maxProb=${"%.3f".format(Locale.US, maxProb)}) — falling back to spectrum VAD")
            return hasSpeechBySpectrum(
                samples = analysis.samples,
                reason = "Silero output stuck near zero",
                peakAfterDc = analysis.peakAfterDc,
                rmsAfterDc = analysis.rmsAfterDc
            )
        }

        return false
    }

    private fun hasSpeechBySpectrum(
        samples: FloatArray,
        reason: String,
        peakAfterDc: Float? = null,
        rmsAfterDc: Float? = null
    ): Boolean {
        val result = BleSpeechDetector.detectSpeechBySpectrum(samples, PCM_SAMPLE_RATE)
        val rescued = peakAfterDc != null &&
            rmsAfterDc != null &&
            result.maxBandRatio >= BLE_RESCUE_BAND_RATIO_THRESHOLD &&
            peakAfterDc >= BLE_RESCUE_PEAK_THRESHOLD &&
            rmsAfterDc >= BLE_RESCUE_RMS_THRESHOLD
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

    // --- WAV file builder ---

    private fun buildWavFile(pcmData: ByteArray): File? {
        return try {
            val ctx = getApplication<Application>()
            val file = File(ctx.cacheDir, "ble_audio_${System.currentTimeMillis()}.wav")
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
        _state.value = VoiceState.SPEAKING
        if (ttsReady && text.isNotBlank()) {
            val utterancePrefix = "response_${System.currentTimeMillis()}"
            val chunks = TtsTextFormatter.toSpeakableChunks(
                text = text,
                maxLength = TextToSpeech.getMaxSpeechInputLength()
            )
            if (chunks.isEmpty()) {
                _state.value = VoiceState.READY
                return
            }
            val finalUtteranceId = "${utterancePrefix}_${chunks.lastIndex}"
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    viewModelScope.launch(Dispatchers.Main) {
                        if (utteranceId == finalUtteranceId && _state.value == VoiceState.SPEAKING) {
                            _state.value = VoiceState.READY
                        }
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    viewModelScope.launch(Dispatchers.Main) {
                        if (_state.value == VoiceState.SPEAKING) _state.value = VoiceState.READY
                    }
                }
            })
            if (!speakWithFallbacks(chunks, utterancePrefix, responseLanguageCode)) {
                Log.e(TAG, "Unable to speak response for language=${responseLanguageCode ?: "default"}")
                _errorMessage.value = "音声の読み上げに失敗しました"
                _state.value = VoiceState.READY
            }
        } else {
            _state.value = VoiceState.READY
        }
    }

    fun stopSpeaking() {
        tts?.stop()
        _state.value = VoiceState.READY
    }

    // --- Helpers ---

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
            if (!trySetTtsLocale(locale)) {
                continue
            }
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
        getApplication<Application>()
            .getSharedPreferences("groq_prefs", android.content.Context.MODE_PRIVATE)
            .getString("groq_api_key", "") ?: ""

    private fun releaseRecorder() {
        try { mediaRecorder?.apply { reset(); release() } } catch (_: Exception) {}
        mediaRecorder = null
    }

    override fun onCleared() {
        super.onCleared()
        amplitudePollingJob?.cancel()
        releaseRecorder()
        tts?.stop()
        tts?.shutdown()
        sileroVad?.close()
    }
}
