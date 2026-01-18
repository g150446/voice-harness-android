/* While this template provides a good starting point for using Wear Compose, you can always
 * take a look at https://github.com/android/wear-os-samples/tree/main/ComposeStarter to find the
 * most up to date changes to the libraries and their usages.
 */

package com.g150446.harnessvoice.presentation

import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
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
import com.g150446.harnessvoice.WearGroqPrefs
import com.g150446.harnessvoice.SensorDataLogger
import com.g150446.harnessvoice.SensorMode
import com.g150446.harnessvoice.GestureDetector
import com.g150446.harnessvoice.GestureType
import com.g150446.harnessvoice.GestureDetectionListener
import com.g150446.harnessvoice.presentation.theme.HarnessVoiceTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private var isInitializing by mutableStateOf(true) // Track initialization state
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
    
    // Sensor data logger and gesture detector
    private var sensorDataLogger: SensorDataLogger? = null
    private var gestureDetector: GestureDetector? = null
    private var gestureTestMode: Boolean = false // Track if we're in test mode (true = gesture test mode, false = gesture control mode)
    private var dataCollectionMode: Boolean = false // Enable data collection mode
    private var gestureDetectedMessage by mutableStateOf("") // Message shown when gesture detected in test mode
    private var gestureTestJob: Job? = null // Job to handle test mode timing
    private var closeConfirmationMessage by mutableStateOf("") // Message shown when rotation detected in recording mode
    private var closeConfirmationJob: Job? = null // Job to handle close confirmation timeout
    private var closeConfirmationShownTime: Long = 0 // Timestamp when confirmation was shown (to prevent immediate false detections)
    private var lastRotationDetectionTime: Long = 0 // Timestamp when rotation was detected (to extend cooldown and prevent false flexion)
    private var showTranscriptionInResult by mutableStateOf(false) // Control whether to show transcription in result display
    private var scrollDownRequest by mutableStateOf(0) // Counter to trigger scroll down action
    private var resultDisplayTimeoutJob: Job? = null // Job to track result display timeout (can be cancelled/reset)
    private var errorMessageClearJob: Job? = null // Job to clear error message after timeout
    private var isActivityReady = false // Track if activity is fully initialized

    companion object {
        private const val TAG = "WatchMainActivity"
        private const val RESULT_DISPLAY_TIMEOUT_MS = 15_000L // 15 seconds

        // VAD constants
        private const val VAD_SILENCE_TIMEOUT_MS = 3_600_000L // Disabled for data collection - 1 hour timeout
        private const val VAD_CHECK_INTERVAL_MS = 100L // Check audio every 100ms
        private const val VAD_AMPLITUDE_THRESHOLD = 500.0 // Amplitude threshold for voice detection (adjustable)
        private const val VAD_SAMPLE_RATE = 16000 // Match MediaRecorder sample rate
        private const val VAD_BUFFER_SIZE_MULTIPLIER = 2 // Buffer size multiplier
        private const val VAD_EXIT_MESSAGE_DISPLAY_MS = 5_000L // Show exit message for 5 seconds before closing
    }

    private val settingsChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                // Only process if activity is ready and not recording
                if (!isActivityReady) {
                    Log.d(TAG, "Activity not ready, ignoring settings change")
                    return
                }

                Log.d(TAG, "Settings changed - reloading gesture detection")
                // Reload gesture detection if currently recording or showing results
                if (isRecording || llmResponseText.isNotBlank()) {
                    // Stop sensor logging
                    sensorDataLogger?.stopLogging()

                    // Reinitialize gesture detection with new mode
                    if (isRecording) {
                        initializeGestureDetectionForRecording()
                    } else {
                        initializeGestureDetectionForResultDisplay()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in settings changed receiver", e)
            }
        }
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
        
        // Batch window flag operations for better performance
        window?.let { win ->
            try {
                // Combine all window flags in a single operation
                win.addFlags(
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
                )
                win.decorView.keepScreenOn = true
                
                // Set brightness if supported
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setTurnScreenOn(true)
                    setShowWhenLocked(true)
                }
                
                val layoutParams = win.attributes
                layoutParams?.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
                win.attributes = layoutParams
                
                Log.v(TAG, "Window flags set in onCreate for persistent screen-on") // Suppressed for data collection
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set window flags in onCreate", e)
            }
        }
        
        handleIntentAction(intent)
        
        setTheme(android.R.style.Theme_DeviceDefault)
        
        setContent {
            WearApp(
                isInitializing = isInitializing,
                isRecording = isRecording,
                transcriptionText = transcriptionText,
                llmResponseText = llmResponseText,
                statusMessage = statusMessage,
                exitMessage = exitMessage,
                gestureTestMode = gestureTestMode,
                gestureDetectedMessage = gestureDetectedMessage,
                closeConfirmationMessage = closeConfirmationMessage,
                showTranscriptionInResult = showTranscriptionInResult,
                scrollDownRequest = scrollDownRequest,
                intentAction = intent?.action,
                dataCollectionMode = dataCollectionMode,
                onStartRecording = { requestMicPermissionAndToggle() },
                onStartDataCollection = { startDataCollection() },
                onInitializeGestureDetection = { initializeGestureDetection() },
                onInitializationComplete = { isInitializing = false }
            )
        }

        // Mark activity as ready
        isActivityReady = true
    }

    private fun initWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            // Try FULL_WAKE_LOCK first (most aggressive), fallback to SCREEN_BRIGHT_WAKE_LOCK
            try {
                wakeLock = powerManager.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "HarnessVoice::RecordingWakeLock"
                )
                Log.v(TAG, "FULL_WAKE_LOCK initialized (most aggressive)") // Suppressed for data collection
            } catch (e: Exception) {
                // Fallback to SCREEN_BRIGHT_WAKE_LOCK if FULL_WAKE_LOCK not available
                wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "HarnessVoice::RecordingWakeLock"
                )
                Log.v(TAG, "SCREEN_BRIGHT_WAKE_LOCK initialized (fallback)") // Suppressed for data collection
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
        // Unregister broadcast receiver (mark as not ready first)
        isActivityReady = false
        try {
            unregisterReceiver(settingsChangedReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister settings changed receiver", e)
        }
        // Stop sensor data logger
        sensorDataLogger?.stopLogging()
        sensorDataLogger = null
        
        stopAndReleaseRecorderIfNeeded()
        // VAD cleanup is already handled in stopAndReleaseRecorderIfNeeded, but ensure it's done
        cleanupVAD()
        cancelAutoClose() // Cancel auto-close job if activity is being destroyed
        errorMessageClearJob?.cancel() // Cancel error message clear job
        resultDisplayTimeoutJob?.cancel() // Cancel result display timeout job
        allowScreenOff()
        releaseWakeLock()
    }
    
    override fun onPause() {
        super.onPause()
        // Unregister broadcast receiver when paused
        try {
            unregisterReceiver(settingsChangedReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister settings changed receiver in onPause", e)
        }
        // Only allow screen off if we're not recording and not showing results
        if (!isRecording && llmResponseText.isBlank() && transcriptionText.isBlank()) {
            allowScreenOff()
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Register broadcast receiver for settings changes (only when activity is active)
        try {
            registerReceiver(settingsChangedReceiver, IntentFilter("com.g150446.harnessvoice.ACTION_SETTINGS_CHANGED"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register settings changed receiver in onResume", e)
        }
        // OPTION A: Always disable ambient mode when activity resumes
        // If recording or showing results, ensure screen stays fully on
        disableAmbientMode()
        if (isRecording || (llmResponseText.isNotBlank() && System.currentTimeMillis() - resultDisplayTime < RESULT_DISPLAY_TIMEOUT_MS)) {
            keepScreenOnDirect() // Use direct method when on UI thread
        }
    }

    private fun handleIntentAction(intent: Intent?) {
        when (intent?.action) {
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

    private fun handleGestureDetectedInTestMode(gestureType: GestureType) {
        // Cancel any existing test job
        gestureTestJob?.cancel()
        
        // Show immediate message based on detected type (will be refined by analysis)
        val immediateMessage = when (gestureType) {
            GestureType.FLEXION -> "flexion"
            GestureType.ROTATION -> "rotation"
            GestureType.PALM_TAP -> "tap"
        }
        gestureDetectedMessage = immediateMessage
        Log.d(TAG, "*** Immediate gesture display: $immediateMessage ***")

        // For PALM_TAP, just show message (no analysis needed)
        if (gestureType == GestureType.PALM_TAP) {
            gestureTestJob = wakeLockScope.launch {
                delay(1500)
                gestureDetectedMessage = ""
            }
            return
        }

        // Start comprehensive gesture analysis for 0.5 seconds after detection
        // This will refine the gesture type and update the message
        startGestureAnalysis()

        // After 1.5 seconds total (0.5s analysis + 1s display), return to "waiting gesture" message
        gestureTestJob = wakeLockScope.launch {
            delay(1500)
            gestureDetectedMessage = ""
        }
    }
    
    /**
     * Comprehensive gesture analysis for result display mode.
     * Analyzes gesture for 0.5 seconds and determines type.
     * - Flexion: Scrolls down
     * - Rotation: Returns to recording scene
     */
    private fun startGestureAnalysisForResultDisplay() {
        wakeLockScope.launch {
            val startTime = System.currentTimeMillis()
            val duration = 500L // 0.5 seconds
            val sampleInterval = 20L // 20ms = ~50Hz (matches sensor sample rate)
            
            // Cumulative values (absolute sum)
            var cumulativeGyroX = 0f
            var cumulativeGyroY = 0f
            var cumulativeGyroZ = 0f
            var cumulativeAccelX = 0f
            var cumulativeAccelY = 0f
            var cumulativeAccelZ = 0f
            
            // Peak values (maximum absolute values)
            var peakGyroX = 0f
            var peakGyroY = 0f
            var peakGyroZ = 0f
            var peakAccelX = 0f
            var peakAccelY = 0f
            var peakAccelZ = 0f
            
            var sampleCount = 0
            
            Log.d(TAG, "*** Starting gesture analysis for result display mode (0.5 seconds) ***")
            
            while (System.currentTimeMillis() - startTime < duration) {
                // Get current sensor values from sensor logger
                val gyroValues = sensorDataLogger?.getCurrentGyroValues() ?: Triple(0f, 0f, 0f)
                val accelValues = sensorDataLogger?.getCurrentAccelValues() ?: Triple(0f, 0f, 0f)
                
                // Calculate absolute values for tracking
                val absGyroX = kotlin.math.abs(gyroValues.first)
                val absGyroY = kotlin.math.abs(gyroValues.second)
                val absGyroZ = kotlin.math.abs(gyroValues.third)
                val absAccelX = kotlin.math.abs(accelValues.first)
                val absAccelY = kotlin.math.abs(accelValues.second)
                val absAccelZ = kotlin.math.abs(accelValues.third)
                
                // Accumulate values
                cumulativeGyroX += absGyroX
                cumulativeGyroY += absGyroY
                cumulativeGyroZ += absGyroZ
                cumulativeAccelX += absAccelX
                cumulativeAccelY += absAccelY
                cumulativeAccelZ += absAccelZ
                
                // Track peak values
                if (absGyroX > peakGyroX) peakGyroX = absGyroX
                if (absGyroY > peakGyroY) peakGyroY = absGyroY
                if (absGyroZ > peakGyroZ) peakGyroZ = absGyroZ
                if (absAccelX > peakAccelX) peakAccelX = absAccelX
                if (absAccelY > peakAccelY) peakAccelY = absAccelY
                if (absAccelZ > peakAccelZ) peakAccelZ = absAccelZ
                
                sampleCount++
                
                delay(sampleInterval)
            }
            
            // Determine gesture type based on Option 1: Cumulative Gyro X threshold
            // Threshold: 50 rad/s (flexion max: 3.23, rotation min: 108.64)
            val GESTURE_DISTINCTION_THRESHOLD = 50.0f
            val isRotation = cumulativeGyroX > GESTURE_DISTINCTION_THRESHOLD
            
            Log.d(TAG, "*** Gesture Analysis Results (Result Display Mode) ***")
            Log.d(TAG, "  Cumulative Gyro X: ${String.format("%.4f", cumulativeGyroX)} rad/s")
            Log.d(TAG, "  Gesture Type: ${if (isRotation) "rotation" else "flexion"}")
            
            // Suppress false flexion detection after rotation
            if (isRotation) {
                // Rotation detected - extend cooldown to prevent false flexion from residual motion
                lastRotationDetectionTime = System.currentTimeMillis()
                gestureDetector?.extendCooldownAfterRotation()
                Log.d(TAG, "*** Rotation detected - extending cooldown to 0.5 second ***")
            }
            
            if (isRotation) {
                // Rotation: Return to recording scene
                Log.d(TAG, "*** ROTATION DETECTED - RETURNING TO RECORDING SCENE ***")
                returnToRecordingScene()
            } else {
                // Flexion: Scroll down
                Log.d(TAG, "*** FLEXION DETECTED - SCROLLING DOWN ***")
                triggerScrollDown()
            }
        }
    }
    
    /**
     * Comprehensive gesture analysis for control mode (recording scene).
     * Analyzes gesture for 0.5 seconds and determines type.
     * - Flexion: Toggles recording
     * - Rotation: Shows close confirmation
     */
    private fun startGestureAnalysisForControl() {
        wakeLockScope.launch {
            val startTime = System.currentTimeMillis()
            val duration = 500L // 0.5 seconds
            val sampleInterval = 20L // 20ms = ~50Hz (matches sensor sample rate)
            
            // Cumulative values (absolute sum)
            var cumulativeGyroX = 0f
            var cumulativeGyroY = 0f
            var cumulativeGyroZ = 0f
            var cumulativeAccelX = 0f
            var cumulativeAccelY = 0f
            var cumulativeAccelZ = 0f
            
            // Peak values (maximum absolute values)
            var peakGyroX = 0f
            var peakGyroY = 0f
            var peakGyroZ = 0f
            var peakAccelX = 0f
            var peakAccelY = 0f
            var peakAccelZ = 0f
            
            var sampleCount = 0
            
            Log.d(TAG, "*** Starting gesture analysis for control mode (0.5 seconds) ***")
            
            while (System.currentTimeMillis() - startTime < duration) {
                // Get current sensor values from sensor logger
                val gyroValues = sensorDataLogger?.getCurrentGyroValues() ?: Triple(0f, 0f, 0f)
                val accelValues = sensorDataLogger?.getCurrentAccelValues() ?: Triple(0f, 0f, 0f)
                
                // Calculate absolute values for tracking
                val absGyroX = kotlin.math.abs(gyroValues.first)
                val absGyroY = kotlin.math.abs(gyroValues.second)
                val absGyroZ = kotlin.math.abs(gyroValues.third)
                val absAccelX = kotlin.math.abs(accelValues.first)
                val absAccelY = kotlin.math.abs(accelValues.second)
                val absAccelZ = kotlin.math.abs(accelValues.third)
                
                // Accumulate values
                cumulativeGyroX += absGyroX
                cumulativeGyroY += absGyroY
                cumulativeGyroZ += absGyroZ
                cumulativeAccelX += absAccelX
                cumulativeAccelY += absAccelY
                cumulativeAccelZ += absAccelZ
                
                // Track peak values
                if (absGyroX > peakGyroX) peakGyroX = absGyroX
                if (absGyroY > peakGyroY) peakGyroY = absGyroY
                if (absGyroZ > peakGyroZ) peakGyroZ = absGyroZ
                if (absAccelX > peakAccelX) peakAccelX = absAccelX
                if (absAccelY > peakAccelY) peakAccelY = absAccelY
                if (absAccelZ > peakAccelZ) peakAccelZ = absAccelZ
                
                sampleCount++
                
                delay(sampleInterval)
            }
            
            // Determine gesture type based on Option 1: Cumulative Gyro X threshold
            // Threshold: 50 rad/s (flexion max: 3.23, rotation min: 108.64)
            val GESTURE_DISTINCTION_THRESHOLD = 50.0f
            val isRotation = cumulativeGyroX > GESTURE_DISTINCTION_THRESHOLD
            
            Log.d(TAG, "*** Gesture Analysis Results (Control Mode) ***")
            Log.d(TAG, "  Cumulative Gyro X: ${String.format("%.4f", cumulativeGyroX)} rad/s")
            Log.d(TAG, "  Gesture Type: ${if (isRotation) "rotation" else "flexion"}")
            
            // Suppress false flexion detection after rotation
            if (isRotation) {
                // Rotation detected - extend cooldown to prevent false flexion from residual motion
                lastRotationDetectionTime = System.currentTimeMillis()
                gestureDetector?.extendCooldownAfterRotation()
                Log.d(TAG, "*** Rotation detected - extending cooldown to 0.5 second ***")
            }
            
            if (isRotation) {
                // Rotation: Show close confirmation
                Log.d(TAG, "*** ROTATION DETECTED - SHOWING CLOSE CONFIRMATION ***")
                showCloseConfirmation()
            } else {
                // Flexion: Toggle recording
                Log.d(TAG, "*** FLEXION DETECTED - TOGGLING RECORDING ***")
                toggleRecording()
            }
        }
    }
    
    /**
     * Shows close message when rotation is detected.
     * App will close automatically after 3 seconds - no cancellation possible.
     */
    private fun showCloseConfirmation() {
        // Cancel any existing confirmation job
        closeConfirmationJob?.cancel()
        
        // Show close message
        closeConfirmationMessage = "Rotation detected\nClosing app..."
        closeConfirmationShownTime = System.currentTimeMillis() // Track when confirmation was shown
        
        // Set timeout: close app after 3 seconds
        closeConfirmationJob = wakeLockScope.launch {
            delay(3000) // 3 seconds timeout
            if (closeConfirmationMessage.isNotBlank()) {
                // Timeout: close the app
                // Keep closeConfirmationMessage set so UI continues showing closing message until activity finishes
                Log.d(TAG, "Rotation detected - closing app")
                closeConfirmationShownTime = 0
                finish()
            }
        }
    }
    
    /**
     * Gesture analysis during close confirmation.
     * Uses quick distinction check (0.2-0.3 seconds) to filter false positives from rotation motion.
     * If flexion detected, cancels confirmation. Rotation is ignored.
     */
    private fun startGestureAnalysisForControlDuringConfirmation() {
        wakeLockScope.launch {
            // Check minimum delay: ignore gestures detected too soon after confirmation was shown
            // This prevents false detections from residual motion of the rotation gesture
            val timeSinceConfirmation = System.currentTimeMillis() - closeConfirmationShownTime
            val MIN_DELAY_MS = 250L // 250ms minimum delay after confirmation is shown
            
            if (timeSinceConfirmation < MIN_DELAY_MS) {
                Log.d(TAG, "Gesture detected too soon after confirmation (${timeSinceConfirmation}ms < ${MIN_DELAY_MS}ms) - ignoring to prevent false positive")
                return@launch
            }
            
            // Use shorter analysis window (0.25 seconds) for faster response to intentional gestures
            val startTime = System.currentTimeMillis()
            val duration = 250L // 0.25 seconds (faster than 0.5s for better responsiveness)
            val sampleInterval = 20L // 20ms = ~50Hz
            
            var cumulativeGyroX = 0f
            var sampleCount = 0
            
            Log.d(TAG, "*** Starting quick gesture distinction during confirmation (${duration}ms) ***")
            
            while (System.currentTimeMillis() - startTime < duration) {
                val gyroValues = sensorDataLogger?.getCurrentGyroValues() ?: Triple(0f, 0f, 0f)
                cumulativeGyroX += kotlin.math.abs(gyroValues.first)
                sampleCount++
                delay(sampleInterval)
            }
            
            // Adjust threshold proportionally for shorter analysis window
            // Original: 50 rad/s over 0.5s = 100 rad/s per second
            // New: 25 rad/s over 0.25s = 100 rad/s per second (same rate)
            val GESTURE_DISTINCTION_THRESHOLD = 25.0f
            val isExternalRotation = cumulativeGyroX > GESTURE_DISTINCTION_THRESHOLD
            
            Log.d(TAG, "*** Gesture Analysis Results (During Confirmation) ***")
            Log.d(TAG, "  Cumulative Gyro X: ${String.format("%.4f", cumulativeGyroX)} rad/s")
            Log.d(TAG, "  Gesture Type: ${if (isExternalRotation) "rotation" else "flexion"}")
            Log.d(TAG, "  Time since confirmation: ${timeSinceConfirmation}ms")
            
            if (!isExternalRotation) {
                // Flexion: cancel confirmation
                Log.d(TAG, "*** FLEXION DETECTED DURING CONFIRMATION - CANCELLING ***")
                cancelCloseConfirmation()
            } else {
                // Rotation: ignore (residual motion from original gesture)
                Log.d(TAG, "Rotation during confirmation - ignored (residual motion)")
            }
        }
    }
    
    /**
     * Cancels close confirmation when flexion is detected during confirmation.
     */
    private fun cancelCloseConfirmation() {
        closeConfirmationJob?.cancel()
        closeConfirmationJob = null
        closeConfirmationMessage = ""
        closeConfirmationShownTime = 0 // Reset timestamp
        Log.d(TAG, "Close confirmation cancelled by flexion")
    }
    
    /**
     * Comprehensive gesture analysis for 0.5 seconds after gesture detection.
     * Tracks cumulative and peak values for both gyroscope and accelerometer.
     * Determines gesture type (flexion vs rotation) based on cumulative gyro X.
     * Updates UI message after 0.5 seconds with the determined gesture type.
     */
    private fun startGestureAnalysis() {
        wakeLockScope.launch {
            val startTime = System.currentTimeMillis()
            val duration = 500L // 0.5 seconds
            val sampleInterval = 20L // 20ms = ~50Hz (matches sensor sample rate)
            
            // Cumulative values (absolute sum)
            var cumulativeGyroX = 0f
            var cumulativeGyroY = 0f
            var cumulativeGyroZ = 0f
            var cumulativeAccelX = 0f
            var cumulativeAccelY = 0f
            var cumulativeAccelZ = 0f
            
            // Peak values (maximum absolute values)
            var peakGyroX = 0f
            var peakGyroY = 0f
            var peakGyroZ = 0f
            var peakAccelX = 0f
            var peakAccelY = 0f
            var peakAccelZ = 0f
            
            var sampleCount = 0
            
            Log.d(TAG, "*** Starting gesture analysis for 0.5 seconds after gesture detection ***")
            
            while (System.currentTimeMillis() - startTime < duration) {
                // Get current sensor values from sensor logger
                val gyroValues = sensorDataLogger?.getCurrentGyroValues() ?: Triple(0f, 0f, 0f)
                val accelValues = sensorDataLogger?.getCurrentAccelValues() ?: Triple(0f, 0f, 0f)
                
                // Calculate absolute values for tracking
                val absGyroX = kotlin.math.abs(gyroValues.first)
                val absGyroY = kotlin.math.abs(gyroValues.second)
                val absGyroZ = kotlin.math.abs(gyroValues.third)
                val absAccelX = kotlin.math.abs(accelValues.first)
                val absAccelY = kotlin.math.abs(accelValues.second)
                val absAccelZ = kotlin.math.abs(accelValues.third)
                
                // Accumulate values
                cumulativeGyroX += absGyroX
                cumulativeGyroY += absGyroY
                cumulativeGyroZ += absGyroZ
                cumulativeAccelX += absAccelX
                cumulativeAccelY += absAccelY
                cumulativeAccelZ += absAccelZ
                
                // Track peak values
                if (absGyroX > peakGyroX) peakGyroX = absGyroX
                if (absGyroY > peakGyroY) peakGyroY = absGyroY
                if (absGyroZ > peakGyroZ) peakGyroZ = absGyroZ
                if (absAccelX > peakAccelX) peakAccelX = absAccelX
                if (absAccelY > peakAccelY) peakAccelY = absAccelY
                if (absAccelZ > peakAccelZ) peakAccelZ = absAccelZ
                
                sampleCount++
                
                delay(sampleInterval)
            }
            
            // Log comprehensive analysis results
            Log.d(TAG, "*** Gesture Analysis Results (0.5 seconds after gesture detection) ***")
            Log.d(TAG, "  Sample count: $sampleCount")
            Log.d(TAG, "")
            Log.d(TAG, "  Cumulative Gyro X: ${String.format("%.4f", cumulativeGyroX)} rad/s")
            Log.d(TAG, "  Cumulative Gyro Y: ${String.format("%.4f", cumulativeGyroY)} rad/s")
            Log.d(TAG, "  Cumulative Gyro Z: ${String.format("%.4f", cumulativeGyroZ)} rad/s")
            Log.d(TAG, "  Average Gyro X: ${String.format("%.4f", cumulativeGyroX / sampleCount)} rad/s")
            Log.d(TAG, "  Average Gyro Y: ${String.format("%.4f", cumulativeGyroY / sampleCount)} rad/s")
            Log.d(TAG, "  Average Gyro Z: ${String.format("%.4f", cumulativeGyroZ / sampleCount)} rad/s")
            Log.d(TAG, "")
            Log.d(TAG, "  Peak Gyro X: ${String.format("%.4f", peakGyroX)} rad/s")
            Log.d(TAG, "  Peak Gyro Y: ${String.format("%.4f", peakGyroY)} rad/s")
            Log.d(TAG, "  Peak Gyro Z: ${String.format("%.4f", peakGyroZ)} rad/s")
            Log.d(TAG, "")
            Log.d(TAG, "  Cumulative Accel X: ${String.format("%.4f", cumulativeAccelX)} m/s²")
            Log.d(TAG, "  Cumulative Accel Y: ${String.format("%.4f", cumulativeAccelY)} m/s²")
            Log.d(TAG, "  Cumulative Accel Z: ${String.format("%.4f", cumulativeAccelZ)} m/s²")
            Log.d(TAG, "  Average Accel X: ${String.format("%.4f", cumulativeAccelX / sampleCount)} m/s²")
            Log.d(TAG, "  Average Accel Y: ${String.format("%.4f", cumulativeAccelY / sampleCount)} m/s²")
            Log.d(TAG, "  Average Accel Z: ${String.format("%.4f", cumulativeAccelZ / sampleCount)} m/s²")
            Log.d(TAG, "")
            Log.d(TAG, "  Peak Accel X: ${String.format("%.4f", peakAccelX)} m/s²")
            Log.d(TAG, "  Peak Accel Y: ${String.format("%.4f", peakAccelY)} m/s²")
            Log.d(TAG, "  Peak Accel Z: ${String.format("%.4f", peakAccelZ)} m/s²")
            
            // Determine gesture type based on Option 1: Cumulative Gyro X threshold
            // Threshold: 50 rad/s (flexion max: 3.23, rotation min: 108.64)
            val GESTURE_DISTINCTION_THRESHOLD = 50.0f
            val isRotation = cumulativeGyroX > GESTURE_DISTINCTION_THRESHOLD
            val gestureType = if (isRotation) {
                "rotation"
            } else {
                "flexion"
            }
            
            // Suppress false flexion detection after rotation
            // If analysis shows rotation but GestureDetector fired (false flexion), suppress it
            if (isRotation) {
                // Rotation detected - suppress false flexion detection
                lastRotationDetectionTime = System.currentTimeMillis()
                // Extend cooldown in GestureDetector to prevent false flexion from residual motion
                gestureDetector?.extendCooldownAfterRotation()
                Log.d(TAG, "*** Rotation detected - suppressing false flexion, extending cooldown to 1 second ***")
            }
            
            // Update UI message after 0.5 seconds with determined gesture type
            gestureDetectedMessage = gestureType
            Log.d(TAG, "*** Gesture Type Determined: $gestureType (Cumulative Gyro X: ${String.format("%.4f", cumulativeGyroX)} rad/s) ***")
        }
    }
    
    private fun initializeGestureDetection() {
        try {
            // OPTION A: Disable ambient mode and keep screen on
            disableAmbientMode() // Explicitly prevent ambient mode
            keepScreenOnDirect() // Multiple methods to keep screen on
            // Also acquire wake lock as backup
            acquireWakeLock()

            // Read gesture mode setting (default to "flexion" if not set)
            val gestureMode = WearGroqPrefs.getGestureMode(this) ?: "flexion"
            Log.d(TAG, "Gesture mode (test mode): $gestureMode")

            // Initialize sensor monitoring in test mode
            sensorDataLogger = SensorDataLogger(this, SensorMode.GESTURE_TEST_MODE)

            // Initialize gesture detector for test mode with gesture mode
            gestureDetector = GestureDetector(object : GestureDetectionListener {
                override fun onGestureDetected(type: GestureType) {
                    // Test mode: show detection message with gesture type
                    Log.d(TAG, "*** ${type.name} DETECTED (TEST MODE) ***")
                    handleGestureDetectedInTestMode(type)
                }
            }, gestureMode)

            // Start sensor monitoring first
            sensorDataLogger?.startLogging()

            // Connect gesture detector to sensor logger and start calibration
            sensorDataLogger?.setGestureDetector(gestureDetector)
            sensorDataLogger?.notifyGestureDetectorReady()
        } catch (e: Exception) {
            statusMessage = "Failed to initialize gesture detection: ${e.message}"
            Log.e(TAG, "Failed to initialize gesture detection", e)
        }
    }
    
    private fun initializeGestureDetectionForRecording() {
        try {
            // Read gesture mode setting (default to "flexion" if not set)
            val gestureMode = WearGroqPrefs.getGestureMode(this) ?: "flexion"
            Log.d(TAG, "Gesture mode: $gestureMode")

            // Initialize sensor monitoring in control mode
            sensorDataLogger = SensorDataLogger(this, SensorMode.GESTURE_CONTROL_MODE)

            // Initialize gesture detector for control mode with gesture mode
            gestureDetector = GestureDetector(object : GestureDetectionListener {
                override fun onGestureDetected(type: GestureType) {
                    // Control mode: handle based on gesture type
                    // If close confirmation is showing, ignore all gestures (no cancellation)
                    if (closeConfirmationMessage.isNotBlank()) {
                        // During close confirmation: ignore all gestures - app will close automatically
                        Log.d(TAG, "Gesture detected during close confirmation - ignoring (no cancellation)")
                        return
                    }

                    // Handle gesture based on type
                    when (type) {
                        GestureType.PALM_TAP -> {
                            Log.d(TAG, "PALM_TAP DETECTED - TOGGLING RECORDING")
                            toggleRecording()
                        }
                        GestureType.FLEXION, GestureType.ROTATION -> {
                            // Flexion/Rotation mode: analyze gesture to distinguish type
                            startGestureAnalysisForControl()
                        }
                    }
                }
            }, gestureMode)

            // Start sensor monitoring first
            sensorDataLogger?.startLogging()

            // Connect gesture detector to sensor logger and start calibration
            sensorDataLogger?.setGestureDetector(gestureDetector)
            sensorDataLogger?.notifyGestureDetectorReady()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize gesture detection for recording", e)
        }
    }
    
    private fun initializeGestureDetectionForResultDisplay() {
        try {
            Log.d(TAG, "=== INITIALIZING GESTURE DETECTION FOR RESULT DISPLAY ===")
            
            // Stop any existing gesture detection first
            if (sensorDataLogger != null) {
                Log.d(TAG, "Stopping existing sensor logger")
                sensorDataLogger?.stopLogging()
            }
            sensorDataLogger = null
            gestureDetector = null
            
            // Initialize sensor monitoring in control mode (reuse same mode)
            sensorDataLogger = SensorDataLogger(this, SensorMode.GESTURE_CONTROL_MODE)
            Log.d(TAG, "Sensor logger created: ${sensorDataLogger != null}")
            
            // Initialize gesture detector for result display scrolling
            gestureDetector = GestureDetector(object : GestureDetectionListener {
                override fun onGestureDetected(type: GestureType) {
                    // Result display mode: analyze gesture to distinguish type (same algorithm as recording mode)
                    // Flexion scrolls down, rotation returns to recording
                    Log.d(TAG, "*** onGestureDetected() called in result display mode: type=$type ***")
                    // Use same gesture analysis as recording mode to distinguish gesture types
                    startGestureAnalysisForResultDisplay()
                }
            })
            Log.d(TAG, "Gesture detector created: ${gestureDetector != null}")
            
            // Start sensor monitoring first
            sensorDataLogger?.startLogging()
            Log.d(TAG, "Sensor logging started, isLogging should be true")
            
            // Connect gesture detector to sensor logger and start calibration
            sensorDataLogger?.setGestureDetector(gestureDetector)
            sensorDataLogger?.notifyGestureDetectorReady()
            
            Log.d(TAG, "=== Gesture detection initialized for result display scrolling ===")
            Log.d(TAG, "Sensor logger state: ${sensorDataLogger != null}, Gesture detector: ${gestureDetector != null}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize gesture detection for result display", e)
        }
    }
    
    private fun triggerScrollDown() {
        // Increment counter to trigger scroll in composable
        scrollDownRequest++
        Log.d(TAG, "*** triggerScrollDown() called - scrollDownRequest incremented to: $scrollDownRequest ***")
        
        // Reset/extend the timeout when user performs gesture (actively scrolling)
        if (llmResponseText.isNotBlank() && !isRecording) {
            Log.d(TAG, "Gesture detected - extending result display timeout")
            resultDisplayTimeoutJob?.cancel()
            resultDisplayTimeoutJob = null
            resultDisplayTime = System.currentTimeMillis()
            scheduleWakeLockRelease()  // Schedule new 15-second timeout
        }
    }
    
    private fun returnToRecordingScene() {
        Log.d(TAG, "=== RETURNING TO RECORDING SCENE ===")
        
        // Cancel timeout cleanup if in progress (edge case: gesture during timeout cleanup)
        resultDisplayTimeoutJob?.cancel()
        resultDisplayTimeoutJob = null
        Log.d(TAG, "Result display timeout cancelled")
        
        // Clear result display state
        llmResponseText = ""
        transcriptionText = ""
        statusMessage = ""
        showTranscriptionInResult = false
        scrollDownRequest = 0  // Reset scroll counter
        Log.d(TAG, "Result display state cleared")
        
        // Clean up result display gesture detection
        sensorDataLogger?.stopLogging()
        sensorDataLogger = null
        gestureDetector = null
        Log.d(TAG, "Result display gesture detection cleaned up")
        
        // Start new recording
        // If recording fails, show error message for a while and stay in recording scene
        try {
            startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording when returning from result display", e)
            // Error message will be shown by startRecording() error handling
            // Clear error message after 5 seconds (edge case: show error for a while)
            errorMessageClearJob?.cancel()
            errorMessageClearJob = wakeLockScope.launch {
                delay(5000) // Show error for 5 seconds
                if (statusMessage.isNotBlank() && !isRecording) {
                    statusMessage = ""
                    Log.d(TAG, "Error message cleared after timeout")
                }
            }
        }
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
    
    private fun startDataCollection() {
        try {
            // OPTION A: Disable ambient mode and keep screen on
            disableAmbientMode() // Explicitly prevent ambient mode
            keepScreenOnDirect() // Multiple methods to keep screen on
            // Also acquire wake lock as backup
            acquireWakeLock()
            
            // Hardcode data type to "flexion" for now
            val dataType = "flexion"
            
            // Initialize sensor monitoring in data collection mode
            sensorDataLogger = SensorDataLogger(this, SensorMode.DATA_COLLECTION, dataType)
            
            // Start data collection
            sensorDataLogger?.startLogging()
            
            statusMessage = "Collecting $dataType data..."
            Log.d(TAG, "Started $dataType data collection")
        } catch (e: Exception) {
            statusMessage = "Failed to start data collection: ${e.message}"
            Log.e(TAG, "Failed to start data collection", e)
        }
    }

    private fun startRecording() {
        try {
            // Clean up result display gesture detection if active
            if (llmResponseText.isNotBlank() && !isRecording) {
                Log.d(TAG, "=== STARTING RECORDING - Cleaning up result display gesture detection ===")
                resultDisplayTimeoutJob?.cancel()
                resultDisplayTimeoutJob = null
                sensorDataLogger?.stopLogging()
                sensorDataLogger = null
                gestureDetector = null
                Log.d(TAG, "Result display gesture detection cleaned up")
            }
            
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
            showTranscriptionInResult = false // Reset transcription display flag
            Log.v(TAG, "Recording started, screen kept on") // Suppressed for data collection
            
            // Initialize VAD: setup AudioRecord and start monitoring
            if (setupVAD()) {
                startVADMonitoring()
                Log.v(TAG, "VAD monitoring started") // Suppressed for data collection
            } else {
                Log.v(TAG, "VAD setup failed, continuing without VAD") // Suppressed for data collection
            }
            
            // Initialize sensor monitoring in gesture control mode
            initializeGestureDetectionForRecording()
        } catch (e: Exception) {
            statusMessage = "Failed to start recording: ${e.message}"
            stopAndReleaseRecorderIfNeeded()
        }
    }

    private fun stopRecordingAndTranscribe() {
        // Stop sensor data logger
        sensorDataLogger?.stopLogging()
        sensorDataLogger = null
        
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
        statusMessage = "" // Clear status - will show transcription when received
        exitMessage = "" // Reset exit message on manual stop
        showTranscriptionInResult = false // Reset transcription display flag
        // Keep screen on during processing - window flag and wake lock remain active
        keepScreenOn()
        
        val fileToUpload = outputFile
        if (fileToUpload == null || !fileToUpload.exists()) {
            statusMessage = "No audio captured"
            // Keep screen on and schedule release even if no audio was captured
            keepScreenOn()
            resultDisplayTime = System.currentTimeMillis()
            scheduleWakeLockRelease()
            // Initialize gesture detection for result display scrolling (even on error)
            initializeGestureDetectionForResultDisplay()
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
            // Initialize gesture detection for result display scrolling (even on error)
            initializeGestureDetectionForResultDisplay()
            Log.d(TAG, "Missing API key error displayed, screen will stay on for 15 seconds")
            return
        }
        // Show "Processing..." message with same style as recording scene
        statusMessage = "Processing..."
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
                        // Initialize gesture detection for result display scrolling (even on error)
                        initializeGestureDetectionForResultDisplay()
                        Log.d(TAG, "Groq error displayed, screen will stay on for 15 seconds")
                    }
                } else {
                    val text = responseBody.ifBlank { "(no text)" }
                    runOnUiThread {
                        transcriptionText = text
                        statusMessage = "" // Clear status message to show transcription
                        showTranscriptionInResult = true // Show transcription for 1 second
                    }
                    // Wait 1 second to show transcription before calling LLM
                    delay(1000)
                    runOnUiThread {
                        showTranscriptionInResult = false // Hide transcription in result display
                        statusMessage = "" // Don't show "Generating..." - transcription will be shown instead
                    }
                    // Call Groq Chat Completions with transcription
                    val chat = callGroqChat(apiKey, text)
                    runOnUiThread {
                        llmResponseText = chat.ifBlank { "(no response)" }
                        statusMessage = "" // Don't show "Done" in result display
                        // Keep screen on and schedule release after 15 seconds
                        keepScreenOn()
                        resultDisplayTime = System.currentTimeMillis()
                        scheduleWakeLockRelease()
                        // Initialize gesture detection for result display scrolling
                        initializeGestureDetectionForResultDisplay()
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
                    // Initialize gesture detection for result display scrolling (even on error)
                    initializeGestureDetectionForResultDisplay()
                    Log.d(TAG, "Upload error displayed, screen will stay on for 15 seconds")
                }
            } finally {
                try { fileToUpload.delete() } catch (_: Exception) {}
            }
        }
    }

    private fun stopAndReleaseRecorderIfNeeded() {
        // Stop sensor data logger
        sensorDataLogger?.stopLogging()
        sensorDataLogger = null
        
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
            Log.v(TAG, "VAD AudioRecord setup successful") // Suppressed for data collection
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
                            // Suppressed: VAD logs to reduce logcat noise during data collection
                        } else {
                            val silenceDuration = System.currentTimeMillis() - lastVoiceActivityTime
                            // Suppressed: "Silence detected" logs to reduce logcat noise
                        }
                        
                        // Check if silence timeout exceeded
                        val silenceDuration = System.currentTimeMillis() - lastVoiceActivityTime
                        if (silenceDuration >= VAD_SILENCE_TIMEOUT_MS) {
                            Log.v(TAG, "Silence timeout exceeded (${silenceDuration}ms), auto-stopping recording") // Suppressed for data collection
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
            } catch (e: CancellationException) {
                // Expected when job is cancelled - don't log as error
                // Re-throw to propagate cancellation properly
                throw e
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
            
            Log.v(TAG, "VAD resources cleaned up") // Suppressed for data collection
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
        // Cancel any existing timeout job
        resultDisplayTimeoutJob?.cancel()
        
        // Schedule screen off 15 seconds after result is displayed
        resultDisplayTimeoutJob = wakeLockScope.launch {
            delay(RESULT_DISPLAY_TIMEOUT_MS)
            // Check if rotation was detected during timeout (edge case: gesture during timeout cleanup)
            // If llmResponseText is blank, user already returned to recording scene
            if (llmResponseText.isBlank() || isRecording) {
                Log.d(TAG, "Result display already cleared or recording started - skipping timeout cleanup")
                resultDisplayTimeoutJob = null
                return@launch
            }
            
            // Clean up gesture detection for result display
            Log.d(TAG, "=== RESULT DISPLAY TIMEOUT - Cleaning up gesture detection ===")
            if (llmResponseText.isNotBlank() && !isRecording) {
                Log.d(TAG, "Stopping sensor logger and clearing gesture detector")
                sensorDataLogger?.stopLogging()
                sensorDataLogger = null
                gestureDetector = null
                Log.d(TAG, "Gesture detection cleaned up after result display timeout")
            } else {
                Log.d(TAG, "Skipping cleanup - llmResponseText blank: ${llmResponseText.isBlank()}, isRecording: $isRecording")
            }
            allowScreenOff()
            releaseWakeLock()
            Log.d(TAG, "Screen can turn off after 15 seconds from result display")
            resultDisplayTimeoutJob = null
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
fun WearApp(
    isInitializing: Boolean,
    isRecording: Boolean,
    transcriptionText: String,
    llmResponseText: String,
    statusMessage: String,
    exitMessage: String,
    gestureTestMode: Boolean,
    gestureDetectedMessage: String,
    closeConfirmationMessage: String,
    showTranscriptionInResult: Boolean,
    scrollDownRequest: Int,
    intentAction: String?,
    dataCollectionMode: Boolean,
    onStartRecording: () -> Unit,
    onStartDataCollection: () -> Unit,
    onInitializeGestureDetection: () -> Unit,
    onInitializationComplete: () -> Unit
) {
    HarnessVoiceTheme {
        // Fast path: Show minimal UI immediately during initialization
        if (isInitializing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colors.background),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Preparing...",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.primary,
                    style = MaterialTheme.typography.title2
                )
            }
            
            // Start initialization immediately (no delay)
            LaunchedEffect(Unit) {
                // Automatically start based on mode (only if not TOGGLE_RECORDING intent)
                if (intentAction != "TOGGLE_RECORDING") {
                    when {
                        dataCollectionMode -> {
                            // Start data collection mode (rotation or flexion)
                            onStartDataCollection()
                        }
                        gestureTestMode -> {
                            // In test mode, just start sensor monitoring without recording
                            onInitializeGestureDetection()
                        }
                        else -> {
                            // Default: start recording with gesture control
                            if (!isRecording) {
                                onStartRecording()
                            }
                        }
                    }
                }
                
                // Mark initialization as complete
                onInitializationComplete()
            }
        } else {
            // Full UI: Only render when initialization is complete
            val scrollState = rememberScrollState()
            val configuration = LocalConfiguration.current
            val density = LocalDensity.current
            
            // Watch for scroll requests and perform instant scroll
            LaunchedEffect(scrollDownRequest) {
                if (scrollDownRequest > 0) {
                    // Calculate 2/3 screen height in pixels for scroll amount
                    val screenHeightDp = configuration.screenHeightDp.dp
                    val screenHeightPx = with(density) { screenHeightDp.toPx() }
                    val scrollAmount = (screenHeightPx * 2f / 3f).toInt()
                    val currentScroll = scrollState.value
                    val maxScroll = scrollState.maxValue
                    // Scroll to current position + scroll amount (instant scroll)
                    val newScrollPosition = (currentScroll + scrollAmount).coerceAtMost(maxScroll)
                    if (newScrollPosition >= maxScroll) {
                        android.util.Log.w("WatchMainActivity", "Scroll reached maximum value ($maxScroll) - cannot scroll further")
                    } else {
                        android.util.Log.d("WatchMainActivity", "Scrolling: $currentScroll -> $newScrollPosition (max: $maxScroll)")
                    }
                    scrollState.scrollTo(newScrollPosition)
                }
            }
            
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
                        isInitializing = isInitializing,
                        isRecording = isRecording,
                        transcriptionText = transcriptionText,
                        llmResponseText = llmResponseText,
                        statusMessage = statusMessage,
                        exitMessage = exitMessage,
                        gestureTestMode = gestureTestMode,
                        gestureDetectedMessage = gestureDetectedMessage,
                        closeConfirmationMessage = closeConfirmationMessage,
                        showTranscriptionInResult = showTranscriptionInResult
                    )
                }
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
fun StatusDisplay(isInitializing: Boolean, isRecording: Boolean, transcriptionText: String, llmResponseText: String, statusMessage: String, exitMessage: String, gestureTestMode: Boolean, gestureDetectedMessage: String, closeConfirmationMessage: String, showTranscriptionInResult: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Show "Preparing..." message during initialization
        if (isInitializing) {
            Text(
                text = "Preparing...",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colors.primary,
                style = MaterialTheme.typography.title2
            )
            return@Column
        }
        
        // Gesture test mode UI (shows detection status instead of recording)
        if (gestureTestMode) {
            if (gestureDetectedMessage.isNotBlank()) {
                // Show "Gesture Detected" message
                Text(
                    text = gestureDetectedMessage,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.primary,
                    style = MaterialTheme.typography.title2
                )
            } else {
                // Show "waiting gesture" message
                Text(
                    text = "Waiting Gesture",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.primary,
                    style = MaterialTheme.typography.title2
                )
            }
            return@Column
        }
        
        // Control mode UI (original recording interface)
        // Show close confirmation message if set (takes priority over recording display)
        if (closeConfirmationMessage.isNotBlank()) {
            Text(
                text = closeConfirmationMessage,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colors.primary,
                style = MaterialTheme.typography.title2
            )
            return@Column
        }
        
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
            // Show transcription during 1-second display period (before result display) or while waiting for LLM
            if (transcriptionText.isNotBlank() && (showTranscriptionInResult || llmResponseText.isBlank())) {
                Text(
                    text = transcriptionText,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.primary, // Same color as "Ask gpt-oss" in recording scene
                    style = MaterialTheme.typography.title2 // Same size as "Ask gpt-oss" in recording scene
                )
            } else if (statusMessage.isNotBlank()) {
                // Show status message (like "Processing...") with same style as recording scene
                Text(
                    text = statusMessage,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.primary, // Same color as "Ask gpt-oss" in recording scene
                    style = MaterialTheme.typography.title2 // Same size as "Ask gpt-oss" in recording scene
                )
            }
            // Result display: Show only LLM response text (no labels, transcription is hidden)
            if (llmResponseText.isNotBlank()) {
                Text(
                    text = llmResponseText,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.onBackground,
                    style = MaterialTheme.typography.caption1
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun DefaultPreview() {
    WearApp(
        isInitializing = false,
        isRecording = false,
        transcriptionText = "Hello world",
        llmResponseText = "Summary: Hello",
        statusMessage = "Done",
        exitMessage = "",
        gestureTestMode = false,
        gestureDetectedMessage = "",
        closeConfirmationMessage = "",
        showTranscriptionInResult = false,
        scrollDownRequest = 0,
        intentAction = null,
        dataCollectionMode = false,
        onStartRecording = {},
        onStartDataCollection = {},
        onInitializeGestureDetection = {},
        onInitializationComplete = {}
    )
}