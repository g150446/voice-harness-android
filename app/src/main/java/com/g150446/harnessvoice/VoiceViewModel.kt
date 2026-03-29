package com.g150446.harnessvoice

import android.app.Application
import android.media.MediaRecorder
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
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

    // BLE state mirrored from BleConnectionService
    private val _bleConnectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val bleConnectionState: StateFlow<BleConnectionState> = _bleConnectionState

    // true when recording is driven by BLE (nRF52840), false when using phone mic
    private val _bleMode = MutableStateFlow(false)
    val bleMode: StateFlow<Boolean> = _bleMode

    // Phone mic recording
    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null

    // BLE PCM accumulation
    private val pcmBuffer = ByteArrayOutputStream()
    private var isCollectingPcm = false

    private val httpClient = OkHttpClient()
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        tts = TextToSpeech(application, this)

        // Mirror BLE connection state
        viewModelScope.launch {
            BleConnectionService.connectionState?.collect { state ->
                _bleConnectionState.value = state
            }
        }

        // React to BLE gesture events (motion settled = gesture trigger)
        viewModelScope.launch {
            BleConnectionService.bleEvents?.collect { event ->
                if (event is BleEvent.GestureDetected) {
                    handleGestureEvent()
                }
            }
        }

        // Accumulate incoming PCM packets during BLE recording
        viewModelScope.launch {
            BleConnectionService.audioPackets?.collect { packet ->
                if (isCollectingPcm) {
                    pcmBuffer.write(packet.pcmData)
                }
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
            ttsReady = true
            Log.d(TAG, "TTS initialized, locale=${Locale.getDefault()}")
        } else {
            Log.e(TAG, "TTS initialization failed: $status")
        }
    }

    // --- Gesture-triggered BLE recording ---

    private fun handleGestureEvent() {
        when (_state.value) {
            VoiceState.READY, VoiceState.ERROR -> startBleRecording()
            VoiceState.RECORDING -> if (_bleMode.value) stopBleRecordingAndProcess()
            else -> { /* ignore gesture while transcribing/responding/speaking */ }
        }
    }

    private fun startBleRecording() {
        if (_state.value != VoiceState.READY && _state.value != VoiceState.ERROR) return
        pcmBuffer.reset()
        isCollectingPcm = true
        _bleMode.value = true
        _transcription.value = ""
        _response.value = ""
        _errorMessage.value = ""
        BleConnectionService.sendCommand(0x01)
        _state.value = VoiceState.RECORDING
        Log.d(TAG, "BLE recording started")
    }

    private fun stopBleRecordingAndProcess() {
        if (_state.value != VoiceState.RECORDING || !_bleMode.value) return
        isCollectingPcm = false
        BleConnectionService.sendCommand(0x00)
        val pcmData = pcmBuffer.toByteArray()
        pcmBuffer.reset()
        _bleMode.value = false
        Log.d(TAG, "BLE recording stopped, ${pcmData.size} bytes of PCM collected")

        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            _errorMessage.value = "Groq API キーが未設定です。設定画面で入力してください。"
            _state.value = VoiceState.ERROR
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val wavFile = buildWavFile(pcmData)
            if (wavFile == null) {
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
            Log.d(TAG, "Phone mic recording started: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            releaseRecorder()
            _errorMessage.value = "録音開始失敗: ${e.message}"
            _state.value = VoiceState.ERROR
        }
    }

    fun stopRecordingAndProcess() {
        if (_state.value != VoiceState.RECORDING) return
        // Delegate to BLE stop if recording via BLE
        if (_bleMode.value) {
            stopBleRecordingAndProcess()
            return
        }

        val file = audioFile ?: return
        try {
            mediaRecorder?.apply { stop(); reset(); release() }
            mediaRecorder = null
            Log.d(TAG, "Phone mic recording stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop recording", e)
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

        try {
            // Step 1: Whisper transcription
            val transcriptionBody = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    file.name,
                    file.asRequestBody(mimeType.toMediaType())
                )
                .addFormDataPart("model", "whisper-large-v3-turbo")
                .addFormDataPart("response_format", "text")
                .build()

            val transcriptionRequest = Request.Builder()
                .url("https://api.groq.com/openai/v1/audio/transcriptions")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(transcriptionBody)
                .build()

            val transcriptionResponse = httpClient.newCall(transcriptionRequest).execute()
            val transcriptionBodyText = transcriptionResponse.body?.string() ?: ""

            if (!transcriptionResponse.isSuccessful) {
                _errorMessage.value = "Whisper error ${transcriptionResponse.code}: ${transcriptionBodyText.take(200)}"
                _state.value = VoiceState.ERROR
                return
            }

            val transcribed = transcriptionBodyText.trim().ifBlank { "(音声なし)" }
            _transcription.value = transcribed
            Log.d(TAG, "Transcription: $transcribed")

            // Step 2: Chat completion
            _state.value = VoiceState.RESPONDING

            val chatJson = JSONObject().apply {
                put("model", "openai/gpt-oss-120b")
                put("messages", org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", transcribed)
                    })
                })
            }.toString()

            val chatRequest = Request.Builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(chatJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val chatResponse = httpClient.newCall(chatRequest).execute()
            val chatBodyText = chatResponse.body?.string().orEmpty()

            if (!chatResponse.isSuccessful) {
                _errorMessage.value = "Chat error ${chatResponse.code}: ${chatBodyText.take(200)}"
                _state.value = VoiceState.ERROR
                return
            }

            val chatObj = JSONObject(chatBodyText)
            val choices = chatObj.optJSONArray("choices")
            val responseText = if (choices != null && choices.length() > 0) {
                choices.getJSONObject(0).optJSONObject("message")?.optString("content").orEmpty()
            } else {
                "(返答なし)"
            }

            _response.value = responseText.ifBlank { "(返答なし)" }
            Log.d(TAG, "Response: $responseText")

            // Step 3: TTS playback
            speakResponse(responseText)

        } catch (e: Exception) {
            Log.e(TAG, "Error during transcribe/respond", e)
            _errorMessage.value = "エラー: ${e.message}"
            _state.value = VoiceState.ERROR
        } finally {
            try { file.delete() } catch (_: Exception) {}
        }
    }

    // --- WAV file builder (for BLE PCM data) ---

    private fun buildWavFile(pcmData: ByteArray): File? {
        return try {
            val ctx = getApplication<Application>()
            val file = File(ctx.cacheDir, "ble_audio_${System.currentTimeMillis()}.wav")
            val byteRate = PCM_SAMPLE_RATE * PCM_CHANNELS * PCM_BITS_PER_SAMPLE / 8
            val blockAlign = (PCM_CHANNELS * PCM_BITS_PER_SAMPLE / 8).toShort()
            val dataSize = pcmData.size
            val fileSize = 36 + dataSize

            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray())
                putInt(fileSize)
                put("WAVE".toByteArray())
                put("fmt ".toByteArray())
                putInt(16)                              // PCM chunk size
                putShort(1)                             // PCM format
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
            Log.d(TAG, "WAV file created: ${file.absolutePath} (${file.length()} bytes)")
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
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "response_${System.currentTimeMillis()}")
        }
        _state.value = VoiceState.READY
    }

    fun stopSpeaking() {
        tts?.stop()
        if (_state.value == VoiceState.SPEAKING) {
            _state.value = VoiceState.READY
        }
    }

    // --- Helpers ---

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
        releaseRecorder()
        tts?.stop()
        tts?.shutdown()
    }
}
