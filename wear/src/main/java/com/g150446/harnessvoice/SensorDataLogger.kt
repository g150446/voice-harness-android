package com.g150446.harnessvoice

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

enum class SensorMode {
    DATA_COLLECTION,                // Log gesture data to CSV (flexion, negative_samples, or rotation)
    GESTURE_TEST_MODE,              // Test gesture detection - show detection status on screen
    GESTURE_CONTROL_MODE           // Control mode - toggle recording with gestures
}

class SensorDataLogger(
    private val context: Context,
    private var mode: SensorMode = SensorMode.DATA_COLLECTION,
    private val dataType: String = "rotation" // Data type: "flexion", "negative_samples", or "rotation"
) {
    private val TAG = "SensorDataLogger"
    private val scope = CoroutineScope(Dispatchers.IO)
    
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var sensorEventListener: SensorEventListener? = null
    
    private var csvFile: File? = null
    private var csvWriter: FileWriter? = null
    private var isLogging = false
    private var startTime: Long = 0
    private var gestureJob: Job? = null
    private var currentGestureNumber = 0
    private var isCollectionComplete = false // Track if all 10 gestures are collected
    private var gestureDetectorNullLogged = false // Track if we've logged the null detector warning
    
    // Data buffer - store CSV lines in memory until collection is complete
    // Use synchronized list to prevent ConcurrentModificationException
    private val dataBuffer = Collections.synchronizedList(mutableListOf<String>())
    
    // Store latest sensor values for synchronization
    private var latestAccelValues = Triple(0f, 0f, 0f)
    private var latestGyroValues = Triple(0f, 0f, 0f)
    private var hasAccelData = false
    private var hasGyroData = false
    private var lastWriteTime = 0L
    
    // Thread-safe access to latest gyro values for cumulative calculation
    @Volatile
    private var currentGyroX: Float = 0f
    @Volatile
    private var currentGyroY: Float = 0f
    @Volatile
    private var currentGyroZ: Float = 0f
    
    // Thread-safe access to latest accelerometer values for cumulative calculation
    @Volatile
    private var currentAccelX: Float = 0f
    @Volatile
    private var currentAccelY: Float = 0f
    @Volatile
    private var currentAccelZ: Float = 0f
    
    companion object {
        private const val SENSOR_SAMPLE_RATE = 50 // Hz
        private const val SENSOR_DELAY_US = (1000 / SENSOR_SAMPLE_RATE * 1000).toInt() // microseconds
        private const val BASELINE_DURATION_MS = 3000L // 3 seconds
        private const val GESTURE_INTERVAL_MS = 2000L // 2 seconds between gestures
        private const val TOTAL_GESTURES = 10
    }
    
    fun startLogging() {
        Log.d(TAG, "=== startLogging() called ===")
        if (isLogging) {
            Log.w(TAG, "Already logging")
            return
        }
        
        try {
            sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            gyroscope = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            
            if (accelerometer == null || gyroscope == null) {
                Log.e(TAG, "Sensors not available")
                return
            }
            
            // Initialize data buffer for data collection modes
            if (mode == SensorMode.DATA_COLLECTION) {
                synchronized(dataBuffer) {
                    dataBuffer.clear()
                }
                isCollectionComplete = false
                // Add CSV header to buffer
                synchronized(dataBuffer) {
                    dataBuffer.add("timestamp_ms,accel_x,accel_y,accel_z,gyro_x,gyro_y,gyro_z,gesture_type,gesture_number")
                }
            }
            
            // Create CSV file path (will be used when saving)
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val gestureFolderName = when (mode) {
                SensorMode.DATA_COLLECTION -> dataType // Use data type for folder name
                SensorMode.GESTURE_TEST_MODE -> "flexion" // Default (not used for CSV)
                SensorMode.GESTURE_CONTROL_MODE -> "flexion" // Default (not used for CSV)
            }
            val gestureFolder = File(context.getExternalFilesDir(null), "gesture_data/$gestureFolderName")
            gestureFolder.mkdirs() // Ensure folder exists
            csvFile = File(gestureFolder, "sensor_data_$timestamp.csv")
            // Don't create FileWriter yet - we'll create it only when saving
            
            isLogging = true
            startTime = System.currentTimeMillis()
            currentGestureNumber = 0
            Log.d(TAG, "isLogging set to TRUE, startTime=$startTime")
            
            when (mode) {
                SensorMode.DATA_COLLECTION -> {
                    Log.d(TAG, "Data collection started (type: $dataType) - logging to file: ${csvFile?.name}")
                    startGestureSequence()
                }
                SensorMode.GESTURE_TEST_MODE -> {
                    Log.d(TAG, "Sensor monitoring started (gesture test mode)")
                }
                SensorMode.GESTURE_CONTROL_MODE -> {
                    Log.d(TAG, "Sensor monitoring started (gesture control mode)")
                }
            }
            
            // Register sensor listeners
            sensorEventListener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (!isLogging) {
                        return
                    }
                    
                    val currentTime = System.currentTimeMillis()
                    val elapsedTime = currentTime - startTime
                    
                    // Determine gesture type and number based on timing
                    val gestureInfo = getGestureInfo(elapsedTime)
                    
                    when (event.sensor.type) {
                        Sensor.TYPE_ACCELEROMETER -> {
                            latestAccelValues = Triple(event.values[0], event.values[1], event.values[2])
                            hasAccelData = true
                            
                            // Update volatile values for thread-safe access
                            currentAccelX = event.values[0]
                            currentAccelY = event.values[1]
                            currentAccelZ = event.values[2]
                        }
                        Sensor.TYPE_GYROSCOPE -> {
                            latestGyroValues = Triple(event.values[0], event.values[1], event.values[2])
                            // Update volatile values for thread-safe access
                            currentGyroX = event.values[0]
                            currentGyroY = event.values[1]
                            currentGyroZ = event.values[2]
                            hasGyroData = true
                        }
                    }
                    
                    // Forward to gesture detector if in test or control mode (forward both accel and gyro events)
                    // Always forward events, even during calibration
                    if (mode == SensorMode.GESTURE_TEST_MODE || mode == SensorMode.GESTURE_CONTROL_MODE) {
                        if (gestureDetector != null) {
                            gestureDetector?.onSensorChanged(event)
                        } else {
                            // Only log once when detector becomes null
                            if (!gestureDetectorNullLogged) {
                                Log.w(TAG, "Sensor event received but gestureDetector is null - events not being forwarded")
                                gestureDetectorNullLogged = true
                            }
                        }
                    }
                    
                    // Buffer data in memory only in data collection modes (don't write to file yet)
                    if (mode == SensorMode.DATA_COLLECTION 
                        && hasAccelData && hasGyroData && (currentTime - lastWriteTime >= 20)) {
                        bufferData(currentTime, gestureInfo.first, gestureInfo.second)
                        lastWriteTime = currentTime
                    }
                }
                
                override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                    // Ignore
                }
            }
            
            sensorManager?.registerListener(
                sensorEventListener,
                accelerometer,
                SENSOR_DELAY_US
            )
            sensorManager?.registerListener(
                sensorEventListener,
                gyroscope,
                SENSOR_DELAY_US
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting sensor logging", e)
            stopLogging()
        }
    }
    
    private fun startGestureSequence() {
        gestureJob = scope.launch {
            // Baseline phase (0-3 seconds)
            if (mode == SensorMode.DATA_COLLECTION) {
                Log.d(TAG, "Baseline calibration started (3 seconds)")
                delay(BASELINE_DURATION_MS)
                Log.d(TAG, "Baseline calibration complete")
                
                // Gesture prompts (every 2 seconds, 10 times)
                val gesturePrompt = when (dataType) {
                    "flexion" -> "START WRIST FLEXION NOW"
                    "negative_samples" -> "START NEGATIVE SAMPLE NOW"
                    "rotation" -> "START ROTATION NOW"
                    else -> "START GESTURE NOW"
                }
                for (gestureNum in 1..TOTAL_GESTURES) {
                    currentGestureNumber = gestureNum
                    Log.d(TAG, "*** GESTURE $gestureNum/$TOTAL_GESTURES - $gesturePrompt ***")
                    
                    if (gestureNum < TOTAL_GESTURES) {
                        delay(GESTURE_INTERVAL_MS)
                    }
                }
                
                // All gestures collected - mark as complete and save data
                isCollectionComplete = true
                Log.d(TAG, "All $TOTAL_GESTURES gestures collected. Saving data to file...")
                saveBufferedData()
            }
        }
    }
    
    private fun getGestureInfo(elapsedTime: Long): Pair<String, Int> {
        return when {
            elapsedTime < BASELINE_DURATION_MS -> Pair("baseline", 0)
            else -> {
                val gestureStartTime = BASELINE_DURATION_MS + (currentGestureNumber - 1) * GESTURE_INTERVAL_MS
                val gestureEndTime = gestureStartTime + GESTURE_INTERVAL_MS
                
                val gestureTypeName = when (mode) {
                    SensorMode.DATA_COLLECTION -> {
                        // Use data type, but convert "negative_samples" to "negative" for CSV
                        when (dataType) {
                            "negative_samples" -> "negative"
                            else -> dataType
                        }
                    }
                    SensorMode.GESTURE_TEST_MODE -> "idle" // Should not be collecting in test mode
                    SensorMode.GESTURE_CONTROL_MODE -> "idle" // Should not be collecting in control mode
                }
                if (elapsedTime >= gestureStartTime && elapsedTime < gestureEndTime) {
                    Pair(gestureTypeName, currentGestureNumber)
                } else {
                    // Between gestures or after last gesture
                    val gestureNum = ((elapsedTime - BASELINE_DURATION_MS) / GESTURE_INTERVAL_MS + 1).toInt().coerceIn(1, TOTAL_GESTURES)
                    Pair(gestureTypeName, gestureNum)
                }
            }
        }
    }
    
    private fun bufferData(
        timestamp: Long,
        gestureType: String,
        gestureNumber: Int
    ) {
        // Add data line to buffer instead of writing to file
        // Synchronized access to prevent concurrent modification
        synchronized(dataBuffer) {
            val csvLine = "$timestamp,${latestAccelValues.first},${latestAccelValues.second},${latestAccelValues.third},${latestGyroValues.first},${latestGyroValues.second},${latestGyroValues.third},$gestureType,$gestureNumber"
            dataBuffer.add(csvLine)
        }
    }
    
    private fun saveBufferedData() {
        if (csvFile == null) {
            Log.e(TAG, "Cannot save data: CSV file path not set")
            return
        }
        
        // Create a synchronized copy of the buffer to avoid ConcurrentModificationException
        val bufferCopy: List<String>
        synchronized(dataBuffer) {
            if (dataBuffer.isEmpty()) {
                Log.w(TAG, "No data to save - buffer is empty")
                return
            }
            // Create a copy to avoid concurrent modification during iteration
            bufferCopy = ArrayList(dataBuffer)
        }
        
        try {
            csvWriter = FileWriter(csvFile, false) // Overwrite mode
            // Write all buffered data from the copy
            for (line in bufferCopy) {
                csvWriter?.write("$line\n")
            }
            csvWriter?.flush()
            csvWriter?.close()
            csvWriter = null
            
            Log.d(TAG, "Data saved successfully: ${csvFile?.name} (${bufferCopy.size} lines)")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving buffered data to CSV", e)
        }
    }
    
    fun stopLogging() {
        Log.d(TAG, "=== stopLogging() called ===")
        if (!isLogging) {
            Log.w(TAG, "stopLogging() called but isLogging is already false")
            return
        }
        
        Log.d(TAG, "Stopping sensor logging, unregistering listeners")
        isLogging = false
        gestureJob?.cancel()
        gestureJob = null
        Log.d(TAG, "isLogging set to FALSE")
        
        sensorManager?.unregisterListener(sensorEventListener)
        sensorEventListener = null
        
        try {
            val totalGestures = currentGestureNumber
            
            // For data collection modes: only save if collection is complete
            if (mode == SensorMode.DATA_COLLECTION) {
                if (isCollectionComplete && totalGestures >= TOTAL_GESTURES) {
                    // Collection was complete - data should already be saved, but ensure it's closed
                    csvWriter?.close()
                    csvWriter = null
                    Log.d(TAG, "Data collection completed (type: $dataType). File saved: ${csvFile?.name}. Total gestures: $totalGestures")
                } else {
                    // Collection was incomplete - discard data
                    synchronized(dataBuffer) {
                        dataBuffer.clear()
                    }
                    csvWriter?.close()
                    csvWriter = null
                    // Delete the file if it was created
                    csvFile?.delete()
                    Log.d(TAG, "Data collection stopped early (type: $dataType). Data discarded. Collected: $totalGestures/$TOTAL_GESTURES gestures")
                }
            } else {
                // Not a data collection mode - just close writer if exists
                csvWriter?.close()
                csvWriter = null
                when (mode) {
                    SensorMode.GESTURE_TEST_MODE -> {
                        Log.d(TAG, "Sensor monitoring stopped (gesture test mode)")
                    }
                    SensorMode.GESTURE_CONTROL_MODE -> {
                        Log.d(TAG, "Sensor monitoring stopped (gesture control mode)")
                    }
                    else -> {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping logging", e)
        }
    }
    
    fun setMode(newMode: SensorMode) {
        mode = newMode
        Log.d(TAG, "Sensor mode changed to: $newMode")
    }
    
    fun getMode(): SensorMode = mode
    
    // Allow external access to sensor events for gesture detector
    private var gestureDetector: GestureDetector? = null
    
    fun setGestureDetector(detector: GestureDetector?) {
        gestureDetector = detector
        gestureDetectorNullLogged = false // Reset flag when detector is set
        // Don't start calibration here - wait for notifyGestureDetectorReady()
        // to ensure sensors are already registering
    }
    
    // Call this after starting logging to trigger calibration
    fun notifyGestureDetectorReady() {
        if (gestureDetector != null && (mode == SensorMode.GESTURE_TEST_MODE || mode == SensorMode.GESTURE_CONTROL_MODE) && isLogging) {
            // Small delay to ensure sensor events are flowing
            scope.launch {
                delay(50) // Give sensors 50ms to start registering
                gestureDetector?.startCalibration()
            }
        }
    }
    
    // Get current gyro values (thread-safe for cumulative calculation)
    fun getCurrentGyroValues(): Triple<Float, Float, Float> {
        return Triple(currentGyroX, currentGyroY, currentGyroZ)
    }
    
    // Get current accelerometer values (thread-safe for cumulative calculation)
    fun getCurrentAccelValues(): Triple<Float, Float, Float> {
        return Triple(currentAccelX, currentAccelY, currentAccelZ)
    }
}
