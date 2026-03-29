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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAG = "VoiceViewModel"
private const val PCM_SAMPLE_RATE = 16000
private const val PCM_CHANNELS = 1
private const val PCM_BITS_PER_SAMPLE = 16

// VAD thresholds
private const val VAD_AMPLITUDE_THRESHOLD = 500   // MediaRecorder.getMaxAmplitude() range 0-32767

// Spectral VAD for BLE PCM (FFT-based, 300-3400 Hz speech band)
private const val SPEECH_LOW_HZ  = 300
private const val SPEECH_HIGH_HZ = 3400
// Minimum ratio of speech-band energy to total energy (noise ~0.39, speech ~0.55+)
private const val SPEECH_RATIO_THRESHOLD = 0.50
// Minimum fraction of frames that must pass both tests to classify as speech
private const val SPEECH_FRAME_MIN_RATIO = 0.05

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

    private val httpClient = OkHttpClient()
    private var tts: TextToSpeech? = null
    private var ttsReady = false

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
            tts?.language = Locale.getDefault()
            ttsReady = true
            Log.d(TAG, "TTS initialized, locale=${Locale.getDefault()}")
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

        try {
            val transcriptionBody = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", file.name, file.asRequestBody(mimeType.toMediaType()))
                .addFormDataPart("model", "whisper-large-v3-turbo")
                .addFormDataPart("response_format", "text")
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

            val transcribed = transcriptionBodyText.trim().ifBlank { "(音声なし)" }
            _transcription.value = transcribed
            Log.d(TAG, "Transcription: $transcribed")

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
     * Spectral VAD: classify BLE PCM as speech if enough frames have their energy
     * concentrated in the 300-3400 Hz speech band.
     *
     * Background reasoning:
     *   - White/pink noise distributes energy uniformly → ~39% falls in speech band
     *     (bins 9-108 out of 255 for a 512-point FFT at 16 kHz)
     *   - Human speech concentrates energy in formant bands → ratio > 50%
     *   - nRF52840 electrical noise floor sits at RMS ~730 but is broadband,
     *     so simple amplitude thresholding fails; spectral ratio is robust.
     */
    private fun hasSpeechInPcm(pcmData: ByteArray): Boolean {
        val frameSize = 512  // must be power of 2; yields ~32 ms frames at 16 kHz
        val hopSize   = 256
        val nSamples  = pcmData.size / 2
        if (nSamples < frameSize) {
            Log.d(TAG, "VAD: audio too short ($nSamples samples) — skipping")
            return false
        }

        val speechLowBin  = SPEECH_LOW_HZ  * frameSize / PCM_SAMPLE_RATE  // 9
        val speechHighBin = SPEECH_HIGH_HZ * frameSize / PCM_SAMPLE_RATE  // 108

        var speechFrames = 0
        var totalFrames  = 0
        var offset = 0

        val re = DoubleArray(frameSize)
        val im = DoubleArray(frameSize)

        while (offset + frameSize <= nSamples) {
            im.fill(0.0)
            for (i in 0 until frameSize) {
                val byteIdx = (offset + i) * 2
                val lo = pcmData[byteIdx].toInt() and 0xFF
                val hi = pcmData[byteIdx + 1].toInt()          // signed high byte
                val sample = ((hi shl 8) or lo).toShort().toDouble()
                // Hann window to reduce spectral leakage
                val win = 0.5 * (1.0 - cos(2.0 * PI * i / (frameSize - 1)))
                re[i] = sample * win
            }

            fftInPlace(re, im)

            var speechEnergy = 0.0
            var totalEnergy  = 0.0
            for (bin in 1 until frameSize / 2) {
                val mag = re[bin] * re[bin] + im[bin] * im[bin]
                totalEnergy += mag
                if (bin in speechLowBin..speechHighBin) speechEnergy += mag
            }

            val ratio = if (totalEnergy > 0.0) speechEnergy / totalEnergy else 0.0
            if (ratio > SPEECH_RATIO_THRESHOLD) speechFrames++
            totalFrames++
            offset += hopSize
        }

        val frameRatio = if (totalFrames > 0) speechFrames.toDouble() / totalFrames else 0.0
        Log.d(TAG, "VAD: $speechFrames/$totalFrames speech frames (${"%.1f".format(frameRatio * 100)}%, threshold=${(SPEECH_FRAME_MIN_RATIO * 100).toInt()}%)")
        return frameRatio >= SPEECH_FRAME_MIN_RATIO
    }

    /** In-place radix-2 Cooley-Tukey FFT. Input length must be a power of 2. */
    private fun fftInPlace(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        // Bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        // Butterfly stages
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang); val wIm = sin(ang)
            var i = 0
            while (i < n) {
                var cRe = 1.0; var cIm = 0.0
                for (k in 0 until len / 2) {
                    val uRe = re[i + k];          val uIm = im[i + k]
                    val vRe = re[i+k+len/2]*cRe - im[i+k+len/2]*cIm
                    val vIm = re[i+k+len/2]*cIm + im[i+k+len/2]*cRe
                    re[i + k]        = uRe + vRe;  im[i + k]        = uIm + vIm
                    re[i+k+len/2]    = uRe - vRe;  im[i+k+len/2]    = uIm - vIm
                    val nCRe = cRe*wRe - cIm*wIm;  cIm = cRe*wIm + cIm*wRe;  cRe = nCRe
                }
                i += len
            }
            len = len shl 1
        }
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
            val uid = "response_${System.currentTimeMillis()}"
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    viewModelScope.launch(Dispatchers.Main) {
                        if (_state.value == VoiceState.SPEAKING) _state.value = VoiceState.READY
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    viewModelScope.launch(Dispatchers.Main) {
                        if (_state.value == VoiceState.SPEAKING) _state.value = VoiceState.READY
                    }
                }
            })
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, uid)
        } else {
            _state.value = VoiceState.READY
        }
    }

    fun stopSpeaking() {
        tts?.stop()
        _state.value = VoiceState.READY
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
        amplitudePollingJob?.cancel()
        releaseRecorder()
        tts?.stop()
        tts?.shutdown()
    }
}
