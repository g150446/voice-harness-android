/* While this template provides a good starting point for using Wear Compose, you can always
 * take a look at https://github.com/android/wear-os-samples/tree/main/ComposeStarter to find the
 * most up to date changes to the libraries and their usages.
 */

package com.g150446.ringxwatch.presentation

import android.content.Intent
import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.media.MediaRecorder
import android.media.AudioRecord
import android.media.AudioFormat
import android.os.Build
import android.os.PowerManager
import android.view.WindowManager
import android.view.Window
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.tooling.preview.devices.WearDevices
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import com.g150446.ringxwatch.CounterManager
import com.g150446.ringxwatch.WearGroqPrefs
import com.g150446.ringxwatch.presentation.theme.RingXwatchTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private lateinit var counterManager: CounterManager
    private var currentCount by mutableStateOf(0)
    private var isRecording by mutableStateOf(false)
    private var transcriptionText by mutableStateOf("")
    private var llmResponseText by mutableStateOf("")
    private var statusMessage by mutableStateOf("")
    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var resultDisplayTime: Long = 0
    private val wakeLockScope = CoroutineScope(Dispatchers.Main)
    
    // VAD components
    private var audioRecord: AudioRecord? = null
    private var vadJob: Job? = null
    private var lastVoiceActivityTime: Long = 0
    private var exitMessage by mutableStateOf("") // Message shown before auto-exit
    private var autoCloseJob: Job? = null // Job for auto-closing after exit message
    
    companion object {
        private const val TAG = "WatchMainActivity"
        private const val RESULT_DISPLAY_TIMEOUT_MS = 15_000L // 15 seconds
        
        // VAD constants
        private const val VAD_SILENCE_TIMEOUT_MS = 5_000L // 5 seconds of silence triggers auto-stop
        private const val VAD_CHECK_INTERVAL_MS = 100L // Check audio every 100ms
        private const val VAD_AMPLITUDE_THRESHOLD = 500.0 // Amplitude threshold for voice detection (adjustable)
        private const val VAD_SAMPLE_RATE = 16000 // Match MediaRecorder sample rate
        private const val VAD_BUFFER_SIZE_MULTIPLIER = 2 // Buffer size multiplier
        private const val VAD_EXIT_MESSAGE_DISPLAY_MS = 5_000L // Show exit message for 5 seconds before closing
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            toggleRecording()
        } else {
            statusMessage = "Microphone permission denied"
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        
        super.onCreate(savedInstanceState)
        
        // Initialize wake lock
        initWakeLock()
        
        // Set window flags early to prevent screen from turning off
        // This helps especially on Wear OS where screen behavior can be different
        window?.let { win ->
            try {
                win.addFlags(
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                )
                win.decorView.keepScreenOn = true
                Log.d(TAG, "Window flags set in onCreate for persistent screen-on")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set window flags in onCreate", e)
            }
        }
        
        // OPTION A: Disable Ambient Mode (affects only this app)
        // Explicitly prevent ambient mode entry - this ONLY affects this activity, NOT other apps
        disableAmbientMode()
        
        // Initialize counter manager
        counterManager = CounterManager(this)
        currentCount = counterManager.getCurrentCount()
        
        handleIntentAction(intent)
        
        // Automatically start recording if not already started by intent action
        // This skips the "Tap ring to start" message and goes directly to recording
        if (intent?.action != "TOGGLE_RECORDING") {
            // Use Handler to start recording after UI is set up
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (!isRecording) {
                    requestMicPermissionAndToggle()
                }
            }, 100) // Small delay to ensure UI is ready
        }
        
        setTheme(android.R.style.Theme_DeviceDefault)
        
        setContent {
            WearApp(currentCount, isRecording, transcriptionText, llmResponseText, statusMessage, exitMessage)
        }
    }
    
    private fun initWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            // Try FULL_WAKE_LOCK first (most aggressive), fallback to SCREEN_BRIGHT_WAKE_LOCK
            try {
                wakeLock = powerManager.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "RingXwatch::RecordingWakeLock"
                )
                Log.d(TAG, "FULL_WAKE_LOCK initialized (most aggressive)")
            } catch (e: Exception) {
                // Fallback to SCREEN_BRIGHT_WAKE_LOCK if FULL_WAKE_LOCK not available
                wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "RingXwatch::RecordingWakeLock"
                )
                Log.d(TAG, "SCREEN_BRIGHT_WAKE_LOCK initialized (fallback)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize wake lock", e)
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntentAction(intent)
    }
    
    
    override fun onDestroy() {
        super.onDestroy()
        counterManager.cleanup()
        stopAndReleaseRecorderIfNeeded()
        // VAD cleanup is already handled in stopAndReleaseRecorderIfNeeded, but ensure it's done
        cleanupVAD()
        cancelAutoClose() // Cancel auto-close job if activity is being destroyed
        allowScreenOff()
        releaseWakeLock()
    }
    
    override fun onPause() {
        super.onPause()
        // Only allow screen off if we're not recording and not showing results
        if (!isRecording && llmResponseText.isBlank() && transcriptionText.isBlank()) {
            allowScreenOff()
        }
    }
    
    override fun onResume() {
        super.onResume()
        // OPTION A: Always disable ambient mode when activity resumes
        // If recording or showing results, ensure screen stays fully on
        disableAmbientMode()
        if (isRecording || (llmResponseText.isNotBlank() && System.currentTimeMillis() - resultDisplayTime < RESULT_DISPLAY_TIMEOUT_MS)) {
            keepScreenOnDirect() // Use direct method when on UI thread
        }
    }

    private fun handleIntentAction(intent: Intent?) {
        when (intent?.action) {
            "INCREMENT_COUNTER" -> {
                val newCount = counterManager.incrementCount()
                currentCount = newCount
            }
            "TOGGLE_RECORDING" -> {
                requestMicPermissionAndToggle()
            }
        }
    }

    private fun requestMicPermissionAndToggle() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return
            }
        }
        toggleRecording()
    }

    private fun toggleRecording() {
        // If exit message is showing, cancel auto-close and restart recording
        if (exitMessage.isNotBlank()) {
            Log.d(TAG, "Ring tap detected during exit message, restarting recording")
            cancelAutoClose()
            exitMessage = ""
            startRecording()
            return
        }
        
        if (isRecording) {
            stopRecordingAndTranscribe()
        } else {
            startRecording()
        }
    }
    
    private fun cancelAutoClose() {
        autoCloseJob?.cancel()
        autoCloseJob = null
        Log.d(TAG, "Auto-close cancelled")
    }

    private fun startRecording() {
        try {
            // OPTION A: Disable ambient mode and keep screen on during recording
            disableAmbientMode() // Explicitly prevent ambient mode
            keepScreenOnDirect() // Multiple methods to keep screen on
            // Also acquire wake lock as backup
            acquireWakeLock()
            
            val file = File(cacheDir, "voice_${System.currentTimeMillis()}.m4a")
            outputFile = file
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64000)
                setAudioSamplingRate(16000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            isRecording = true
            statusMessage = "Recording..."
            exitMessage = "" // Reset exit message when starting new recording
            Log.d(TAG, "Recording started, screen kept on")
            
            // Initialize VAD: setup AudioRecord and start monitoring
            if (setupVAD()) {
                startVADMonitoring()
                Log.d(TAG, "VAD monitoring started")
            } else {
                Log.w(TAG, "VAD setup failed, continuing without VAD")
            }
        } catch (e: Exception) {
            statusMessage = "Failed to start recording: ${e.message}"
            stopAndReleaseRecorderIfNeeded()
        }
    }

    private fun stopRecordingAndTranscribe() {
        // Stop VAD monitoring first
        cleanupVAD()
        
        try {
            mediaRecorder?.apply {
                try { stop() } catch (_: Exception) {}
                reset()
                release()
            }
        } catch (_: Exception) {
        } finally {
            mediaRecorder = null
        }
        isRecording = false
        statusMessage = "Processing..."
        exitMessage = "" // Reset exit message on manual stop
        // Keep screen on during processing - window flag and wake lock remain active
        keepScreenOn()
        
        val fileToUpload = outputFile
        if (fileToUpload == null || !fileToUpload.exists()) {
            statusMessage = "No audio captured"
            // Keep screen on and schedule release even if no audio was captured
            keepScreenOn()
            resultDisplayTime = System.currentTimeMillis()
            scheduleWakeLockRelease()
            Log.d(TAG, "No audio error displayed, screen will stay on for 15 seconds")
            return
        }
        val apiKey = WearGroqPrefs.getApiKey(this)
        if (apiKey.isNullOrBlank()) {
            statusMessage = "Missing Groq API key (set on phone)"
            // Keep screen on and schedule release even if API key is missing
            keepScreenOn()
            resultDisplayTime = System.currentTimeMillis()
            scheduleWakeLockRelease()
            Log.d(TAG, "Missing API key error displayed, screen will stay on for 15 seconds")
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient()
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file",
                        fileToUpload.name,
                        fileToUpload.asRequestBody("audio/mp4".toMediaType())
                    )
                    .addFormDataPart("model", "whisper-large-v3-turbo")
                    .addFormDataPart("response_format", "text")
                    .build()

                val request = Request.Builder()
                    .url("https://api.groq.com/openai/v1/audio/transcriptions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    runOnUiThread {
                        statusMessage = "Groq error ${response.code}"
                        transcriptionText = responseBody.take(400)
                        // Even on error, keep screen on and schedule release after 15 seconds
                        keepScreenOn()
                        resultDisplayTime = System.currentTimeMillis()
                        scheduleWakeLockRelease()
                        Log.d(TAG, "Groq error displayed, screen will stay on for 15 seconds")
                    }
                } else {
                    val text = responseBody.ifBlank { "(no text)" }
                    runOnUiThread {
                        transcriptionText = text
                        statusMessage = "Generating..."
                    }
                    // Call Groq Chat Completions with transcription
                    val chat = callGroqChat(apiKey, text)
                    runOnUiThread {
                        llmResponseText = chat.ifBlank { "(no response)" }
                        statusMessage = "Done"
                        // Keep screen on and schedule release after 15 seconds
                        keepScreenOn()
                        resultDisplayTime = System.currentTimeMillis()
                        scheduleWakeLockRelease()
                        Log.d(TAG, "Groq result displayed, screen will stay on for 15 seconds")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusMessage = "Upload failed: ${e.message}"
                    // On upload failure, keep screen on and schedule release after 15 seconds
                    keepScreenOn()
                    resultDisplayTime = System.currentTimeMillis()
                    scheduleWakeLockRelease()
                    Log.d(TAG, "Upload error displayed, screen will stay on for 15 seconds")
                }
            } finally {
                try { fileToUpload.delete() } catch (_: Exception) {}
            }
        }
    }

    private fun stopAndReleaseRecorderIfNeeded() {
        try {
            mediaRecorder?.apply {
                try { stop() } catch (_: Exception) {}
                reset()
                release()
            }
        } catch (_: Exception) {
        } finally {
            mediaRecorder = null
            isRecording = false
        }
        // Clean up VAD resources
        cleanupVAD()
    }
    
    // VAD Methods
    private fun setupVAD(): Boolean {
        return try {
            val bufferSize = AudioRecord.getMinBufferSize(
                VAD_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ) * VAD_BUFFER_SIZE_MULTIPLIER
            
            if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
                Log.e(TAG, "Invalid buffer size for AudioRecord")
                return false
            }
            
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                VAD_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                audioRecord?.release()
                audioRecord = null
                return false
            }
            
            audioRecord?.startRecording()
            Log.d(TAG, "VAD AudioRecord setup successful")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup VAD", e)
            cleanupVAD()
            false
        }
    }
    
    private fun startVADMonitoring() {
        // Cancel any existing VAD job
        vadJob?.cancel()
        
        // Reset voice activity time to current time when starting
        lastVoiceActivityTime = System.currentTimeMillis()
        
        vadJob = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ShortArray(VAD_SAMPLE_RATE / 10) // 100ms of audio at 16kHz
            val audioRecordInstance = audioRecord
            
            if (audioRecordInstance == null) {
                Log.e(TAG, "AudioRecord is null, cannot start VAD monitoring")
                return@launch
            }
            
            try {
                while (isActive && isRecording) {
                    val samplesRead = audioRecordInstance.read(buffer, 0, buffer.size)
                    
                    if (samplesRead > 0) {
                        // Calculate RMS (Root Mean Square) amplitude
                        var sumSquares = 0.0
                        for (i in 0 until samplesRead) {
                            val sample = buffer[i].toDouble()
                            sumSquares += sample * sample
                        }
                        val rms = Math.sqrt(sumSquares / samplesRead)
                        
                        // Check if amplitude exceeds threshold (voice activity detected)
                        if (rms > VAD_AMPLITUDE_THRESHOLD) {
                            lastVoiceActivityTime = System.currentTimeMillis()
                            Log.d(TAG, "Voice activity detected, RMS: $rms")
                        } else {
                            val silenceDuration = System.currentTimeMillis() - lastVoiceActivityTime
                            Log.d(TAG, "Silence detected, RMS: $rms, duration: ${silenceDuration}ms")
                        }
                        
                        // Check if silence timeout exceeded
                        val silenceDuration = System.currentTimeMillis() - lastVoiceActivityTime
                        if (silenceDuration >= VAD_SILENCE_TIMEOUT_MS) {
                            Log.d(TAG, "Silence timeout exceeded (${silenceDuration}ms), auto-stopping recording")
                            runOnUiThread {
                                autoStopRecording()
                            }
                            break
                        }
                    } else if (samplesRead == AudioRecord.ERROR_INVALID_OPERATION) {
                        Log.e(TAG, "AudioRecord read error: INVALID_OPERATION")
                        break
                    } else if (samplesRead == AudioRecord.ERROR_BAD_VALUE) {
                        Log.e(TAG, "AudioRecord read error: BAD_VALUE")
                        break
                    }
                    
                    delay(VAD_CHECK_INTERVAL_MS)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in VAD monitoring", e)
            }
        }
    }
    
    private fun cleanupVAD() {
        try {
            vadJob?.cancel()
            vadJob = null
            
            audioRecord?.let { recorder ->
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop()
                }
                recorder.release()
            }
            audioRecord = null
            
            Log.d(TAG, "VAD resources cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up VAD resources", e)
        }
    }
    
    private fun autoStopRecording() {
        Log.d(TAG, "Auto-stopping recording due to silence timeout")
        
        // Show exit message first
        exitMessage = "Could not detect any voice, exiting.\nTap to restart."
        
        // Stop MediaRecorder immediately but keep UI visible for message
        try {
            mediaRecorder?.apply {
                try { stop() } catch (e: Exception) {
                    Log.e(TAG, "Error stopping MediaRecorder during auto-stop", e)
                }
                try {
                    reset()
                    release()
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing MediaRecorder during auto-stop", e)
                }
            }
            mediaRecorder = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaRecorder", e)
        }
        
        // Clean up VAD
        cleanupVAD()
        
        // Cancel any existing auto-close job
        cancelAutoClose()
        
        // Wait for message display, then finish
        autoCloseJob = CoroutineScope(Dispatchers.Main).launch {
            delay(VAD_EXIT_MESSAGE_DISPLAY_MS)
            
            // Check if exit message was cleared (ring tap detected)
            if (exitMessage.isBlank()) {
                Log.d(TAG, "Exit message cleared, not closing activity")
                return@launch
            }
            
            try {
                // Release wake lock
                releaseWakeLock()
                
                // Allow screen to turn off
                allowScreenOff()
                
                // Reset recording state
                isRecording = false
                statusMessage = ""
                exitMessage = ""
                
                // Close activity and return to home screen
                Log.d(TAG, "Finishing activity to return to home screen")
                finish()
            } catch (e: Exception) {
                Log.e(TAG, "Error in autoStopRecording cleanup", e)
                // Ensure cleanup even on error
                releaseWakeLock()
                allowScreenOff()
                isRecording = false
                exitMessage = ""
                finish()
            }
        }
    }

    private fun disableAmbientMode() {
        // OPTION A: Explicitly disable ambient mode for this activity only
        // This does NOT affect other apps - each app controls its own ambient behavior
        try {
            window?.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_FULLSCREEN // Prevents ambient mode
            )
            window?.decorView?.keepScreenOn = true
            Log.d(TAG, "Ambient mode disabled for this activity (does NOT affect other apps)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable ambient mode", e)
        }
    }
    
    private fun keepScreenOn() {
        // Calls disableAmbientMode to ensure screen stays on and ambient is disabled
        try {
            runOnUiThread {
                disableAmbientMode()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to run keep screen on on UI thread", e)
        }
    }
    
    private fun keepScreenOnDirect() {
        // Direct call when already on UI thread - OPTION A: Disable Ambient Mode + Multiple Methods
        try {
            // Method 1: Modern Activity API (Android 8.0+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setTurnScreenOn(true)
                setShowWhenLocked(true)
            }
            
            // Method 2: Activity.setKeepScreenOn() - modern approach
            window?.decorView?.keepScreenOn = true
            
            // Method 3: Window flags (legacy but reliable) - PREVENTS AMBIENT MODE
            window?.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_FULLSCREEN // Prevents ambient mode entry
            )
            
            // Method 4: Set screen brightness to maximum (helps on Wear OS, keeps it interactive)
            val layoutParams = window?.attributes
            layoutParams?.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
            window?.attributes = layoutParams
            
            Log.d(TAG, "Ambient mode disabled + screen kept on (affects only this app)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set keep screen on methods", e)
        }
    }
    
    private fun allowScreenOff() {
        try {
            // Clear all screen-keeping methods
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setTurnScreenOn(false)
                setShowWhenLocked(false)
            }
            
            window?.decorView?.keepScreenOn = false
            
            window?.clearFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
            
            // Reset brightness
            val layoutParams = window?.attributes
            layoutParams?.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window?.attributes = layoutParams
            
            Log.d(TAG, "All screen-keeping methods cleared - screen can turn off")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear keep screen on methods", e)
        }
    }
    
    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire(10 * 60 * 1000L) // 10 minutes timeout as safety
                Log.d(TAG, "Wake lock acquired - backup screen keep-on")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock", e)
        }
    }
    
    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "Wake lock released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release wake lock", e)
        }
    }
    
    private fun scheduleWakeLockRelease() {
        // Schedule screen off 15 seconds after result is displayed
        wakeLockScope.launch {
            delay(RESULT_DISPLAY_TIMEOUT_MS)
            allowScreenOff()
            releaseWakeLock()
            Log.d(TAG, "Screen can turn off after 15 seconds from result display")
        }
    }
    
    private fun callGroqChat(apiKey: String, transcription: String): String {
        return try {
            val client = OkHttpClient()
            val json = JSONObject().apply {
                put("model", "openai/gpt-oss-120b")
                put("messages", org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", transcription)
                    })
                })
            }.toString()

            val request = Request.Builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return body.take(400)
                }
                val obj = JSONObject(body)
                val choices = obj.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val msg = choices.getJSONObject(0).optJSONObject("message")
                    msg?.optString("content").orEmpty()
                } else {
                    ""
                }
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}

@Composable
fun WearApp(currentCount: Int, isRecording: Boolean, transcriptionText: String, llmResponseText: String, statusMessage: String, exitMessage: String) {
    RingXwatchTheme {
        val scrollState = rememberScrollState()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background)
                .padding(16.dp) // Add padding for round screens
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                StatusDisplay(
                    isRecording = isRecording,
                    transcriptionText = transcriptionText,
                    llmResponseText = llmResponseText,
                    statusMessage = statusMessage,
                    exitMessage = exitMessage,
                    currentCount = currentCount
                )
            }
        }
    }
}

@Composable
fun BlinkingMicIcon() {
    var targetAlpha by mutableStateOf(1f)
    
    LaunchedEffect(Unit) {
        while (true) {
            targetAlpha = 0.2f
            delay(500)
            targetAlpha = 1f
            delay(500)
        }
    }
    
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = 500)
    )
    
    Icon(
        imageVector = Icons.Filled.Mic,
        contentDescription = "Recording",
        tint = MaterialTheme.colors.primary, // Same color as "Ask gpt-oss" text
        modifier = Modifier
            .size(38.dp) // 20% smaller (48 * 0.8 = 38.4)
            .offset(y = 10.dp) // Move 10 pixels lower
            .graphicsLayer(alpha = alpha)
    )
}

@Composable
fun StatusDisplay(isRecording: Boolean, transcriptionText: String, llmResponseText: String, statusMessage: String, exitMessage: String, currentCount: Int) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (isRecording || exitMessage.isNotBlank()) {
            if (exitMessage.isNotBlank()) {
                // Show exit message in primary color (same as "Ask gpt-oss")
                Text(text = exitMessage, textAlign = TextAlign.Center, color = MaterialTheme.colors.primary, style = MaterialTheme.typography.title2)
            } else {
                Text(text = "Ask gpt-oss", textAlign = TextAlign.Center, color = MaterialTheme.colors.primary, style = MaterialTheme.typography.title2)
                Spacer(modifier = Modifier.height(8.dp))
                BlinkingMicIcon()
            }
        } else {
            if (transcriptionText.isNotBlank()) {
                Text(text = "You:", textAlign = TextAlign.Center, color = MaterialTheme.colors.primary, style = MaterialTheme.typography.caption2)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = transcriptionText, textAlign = TextAlign.Center, color = MaterialTheme.colors.onBackground, style = MaterialTheme.typography.caption1)
                Spacer(modifier = Modifier.height(12.dp))
            } else if (statusMessage.isNotBlank()) {
                // Show status message (like "Processing...")
                Text(text = statusMessage, textAlign = TextAlign.Center, color = MaterialTheme.colors.secondaryVariant, style = MaterialTheme.typography.caption2)
            }
            // "Tap ring to start" message removed - app auto-starts recording
            if (llmResponseText.isNotBlank()) {
                Text(text = "gpt-oss:", textAlign = TextAlign.Center, color = MaterialTheme.colors.primary, style = MaterialTheme.typography.caption2)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = llmResponseText, textAlign = TextAlign.Center, color = MaterialTheme.colors.onBackground, style = MaterialTheme.typography.caption1)
                Spacer(modifier = Modifier.height(8.dp))
            }
            // Only show statusMessage at bottom if we're showing transcription (to avoid duplication)
            if (statusMessage.isNotBlank() && transcriptionText.isNotBlank()) {
                Text(text = statusMessage, textAlign = TextAlign.Center, color = MaterialTheme.colors.secondaryVariant, style = MaterialTheme.typography.caption2)
            }
        }
    }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun DefaultPreview() {
    WearApp(currentCount = 42, isRecording = false, transcriptionText = "Hello world", llmResponseText = "Summary: Hello", statusMessage = "Done", exitMessage = "")
}