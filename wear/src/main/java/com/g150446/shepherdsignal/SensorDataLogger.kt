package com.g150446.shepherdsignal

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
import java.util.Date
import java.util.Locale

enum class SensorMode {
    DATA_COLLECTION,    // Log sensor data to CSV, show collection messages
    GESTURE_DETECTION   // Real-time detection, suppress data collection logs
}

class SensorDataLogger(
    private val context: Context,
    private var mode: SensorMode = SensorMode.DATA_COLLECTION
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
    
    // Store latest sensor values for synchronization
    private var latestAccelValues = Triple(0f, 0f, 0f)
    private var latestGyroValues = Triple(0f, 0f, 0f)
    private var hasAccelData = false
    private var hasGyroData = false
    private var lastWriteTime = 0L
    
    companion object {
        private const val SENSOR_SAMPLE_RATE = 50 // Hz
        private const val SENSOR_DELAY_US = (1000 / SENSOR_SAMPLE_RATE * 1000).toInt() // microseconds
        private const val BASELINE_DURATION_MS = 3000L // 3 seconds
        private const val GESTURE_INTERVAL_MS = 2000L // 2 seconds between gestures
        private const val TOTAL_GESTURES = 10
    }
    
    fun startLogging() {
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
            
            // Create CSV file
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            csvFile = File(context.cacheDir, "sensor_data_$timestamp.csv")
            csvWriter = FileWriter(csvFile, true)
            
            // Write CSV header
            csvWriter?.write("timestamp_ms,accel_x,accel_y,accel_z,gyro_x,gyro_y,gyro_z,gesture_type,gesture_number\n")
            csvWriter?.flush()
            
            isLogging = true
            startTime = System.currentTimeMillis()
            currentGestureNumber = 0
            
            if (mode == SensorMode.DATA_COLLECTION) {
                Log.d(TAG, "Data collection started - logging to file: ${csvFile?.name}")
                // Start gesture timing sequence only in data collection mode
                startGestureSequence()
            } else {
                Log.d(TAG, "Sensor monitoring started (gesture detection mode)")
            }
            
            // Register sensor listeners
            sensorEventListener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (!isLogging) return
                    
                    val currentTime = System.currentTimeMillis()
                    val elapsedTime = currentTime - startTime
                    
                    // Determine gesture type and number based on timing
                    val gestureInfo = getGestureInfo(elapsedTime)
                    
                    when (event.sensor.type) {
                        Sensor.TYPE_ACCELEROMETER -> {
                            latestAccelValues = Triple(event.values[0], event.values[1], event.values[2])
                            hasAccelData = true
                        }
                        Sensor.TYPE_GYROSCOPE -> {
                            latestGyroValues = Triple(event.values[0], event.values[1], event.values[2])
                            hasGyroData = true
                        }
                    }
                    
                    // Forward to gesture detector if in detection mode (forward both accel and gyro events)
                    // Always forward events, even during calibration
                    if (mode == SensorMode.GESTURE_DETECTION) {
                        gestureDetector?.onSensorChanged(event)
                    }
                    
                    // Write to CSV only in data collection mode
                    if (mode == SensorMode.DATA_COLLECTION && hasAccelData && hasGyroData && (currentTime - lastWriteTime >= 20)) {
                        writeToCSV(currentTime, gestureInfo.first, gestureInfo.second)
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
                for (gestureNum in 1..TOTAL_GESTURES) {
                    currentGestureNumber = gestureNum
                    Log.d(TAG, "*** GESTURE $gestureNum/$TOTAL_GESTURES - START WRIST FLEXION NOW ***")
                    
                    if (gestureNum < TOTAL_GESTURES) {
                        delay(GESTURE_INTERVAL_MS)
                    }
                }
            }
        }
    }
    
    private fun getGestureInfo(elapsedTime: Long): Pair<String, Int> {
        return when {
            elapsedTime < BASELINE_DURATION_MS -> Pair("baseline", 0)
            else -> {
                val gestureStartTime = BASELINE_DURATION_MS + (currentGestureNumber - 1) * GESTURE_INTERVAL_MS
                val gestureEndTime = gestureStartTime + GESTURE_INTERVAL_MS
                
                if (elapsedTime >= gestureStartTime && elapsedTime < gestureEndTime) {
                    Pair("flexion", currentGestureNumber)
                } else {
                    // Between gestures or after last gesture
                    val gestureNum = ((elapsedTime - BASELINE_DURATION_MS) / GESTURE_INTERVAL_MS + 1).toInt().coerceIn(1, TOTAL_GESTURES)
                    Pair("flexion", gestureNum)
                }
            }
        }
    }
    
    private fun writeToCSV(
        timestamp: Long,
        gestureType: String,
        gestureNumber: Int
    ) {
        try {
            csvWriter?.write("$timestamp,${latestAccelValues.first},${latestAccelValues.second},${latestAccelValues.third},${latestGyroValues.first},${latestGyroValues.second},${latestGyroValues.third},$gestureType,$gestureNumber\n")
            csvWriter?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error writing to CSV", e)
        }
    }
    
    fun stopLogging() {
        if (!isLogging) return
        
        isLogging = false
        gestureJob?.cancel()
        gestureJob = null
        
        sensorManager?.unregisterListener(sensorEventListener)
        sensorEventListener = null
        
        try {
            csvWriter?.close()
            csvWriter = null
            
            val totalGestures = currentGestureNumber
            if (mode == SensorMode.DATA_COLLECTION) {
                Log.d(TAG, "Data collection stopped. File saved: ${csvFile?.name}. Total gestures: $totalGestures")
            } else {
                Log.d(TAG, "Sensor monitoring stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error closing CSV file", e)
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
        // Don't start calibration here - wait for notifyGestureDetectorReady()
        // to ensure sensors are already registering
    }
    
    // Call this after starting logging to trigger calibration
    fun notifyGestureDetectorReady() {
        if (gestureDetector != null && mode == SensorMode.GESTURE_DETECTION && isLogging) {
            // Small delay to ensure sensor events are flowing
            scope.launch {
                delay(50) // Give sensors 50ms to start registering
                gestureDetector?.startCalibration()
            }
        }
    }
}
