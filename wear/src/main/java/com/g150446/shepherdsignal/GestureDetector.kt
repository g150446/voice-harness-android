package com.g150446.shepherdsignal

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.util.Log
import kotlin.math.abs

enum class GestureType {
    WRIST_FLEXION
    // External rotation deferred to future
}

interface GestureDetectionListener {
    fun onGestureDetected(type: GestureType)
}

enum class DetectionState {
    IDLE,           // Monitoring baseline, no gesture detected
    DETECTING,      // Threshold exceeded, tracking peak
    CONFIRMED,      // Peak detected + return to baseline within time window
    COOLDOWN        // Brief period after detection to prevent false positives
}

class GestureDetector(
    private val listener: GestureDetectionListener?
) {
    private val TAG = "GestureDetector"
    
    // State
    private var state = DetectionState.IDLE
    private var baselineMean = BaselineValues()
    private var baselineStd = BaselineValues()
    private var baselineSamples = mutableListOf<SensorSample>()
    private var isCalibrating = false
    private var calibrationStartTime: Long = 0
    private var lastDetectionTime: Long = 0
    
    // Peak tracking
    private var peakTime: Long = 0
    private var peakGyroX: Float = 0f
    private var peakAccelZ: Float = 0f
    private var detectionStartTime: Long = 0
    
    // Store latest sensor values for synchronization
    private var latestAccelValues = Triple(0f, 0f, 0f)
    private var latestGyroValues = Triple(0f, 0f, 0f)
    private var hasAccelData = false
    private var hasGyroData = false
    
    companion object {
        // Wrist Flexion Thresholds
        private const val FLEXION_GYRO_X_THRESHOLD = -4.0f // rad/s (negative = flexion)
        private const val FLEXION_ACCEL_Z_THRESHOLD = 8.0f // m/s² deviation from baseline
        private const val FLEXION_MIN_DURATION_MS = 300L // 0.3s minimum gesture
        private const val FLEXION_MAX_DURATION_MS = 3000L // 3s maximum gesture
        
        // Baseline Calibration
        private const val BASELINE_DURATION_MS = 800L // 0.8 seconds - optimized from analysis
        private const val MIN_BASELINE_SAMPLES = 20 // Minimum samples needed (at 50Hz, 20 samples = 400ms)
        private const val BASELINE_STD_MULTIPLIER = 2.0f // ±2σ for baseline bounds
        
        // Cooldown to prevent false positives
        private const val GESTURE_COOLDOWN_MS = 500L
    }
    
    data class BaselineValues(
        var accelX: Float = 0f,
        var accelY: Float = 0f,
        var accelZ: Float = 0f,
        var gyroX: Float = 0f,
        var gyroY: Float = 0f,
        var gyroZ: Float = 0f
    )
    
    data class SensorSample(
        val timestamp: Long,
        val accelX: Float,
        val accelY: Float,
        val accelZ: Float,
        val gyroX: Float,
        val gyroY: Float,
        val gyroZ: Float
    )
    
    fun startCalibration() {
        // Prevent multiple calibrations
        if (isCalibrating) {
            Log.d(TAG, "Calibration already in progress, ignoring duplicate start")
            return
        }
        
        isCalibrating = true
        calibrationStartTime = System.currentTimeMillis()
        baselineSamples.clear()
        hasAccelData = false
        hasGyroData = false
        state = DetectionState.IDLE
        Log.d(TAG, "Baseline calibration started (${BASELINE_DURATION_MS}ms, min ${MIN_BASELINE_SAMPLES} samples)")
    }
    
    fun onSensorChanged(event: SensorEvent) {
        if (event.values.size < 3) return
        
        val timestamp = System.currentTimeMillis()
        
        // Store latest sensor values
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                latestAccelValues = Triple(event.values[0], event.values[1], event.values[2])
                hasAccelData = true
            }
            Sensor.TYPE_GYROSCOPE -> {
                latestGyroValues = Triple(event.values[0], event.values[1], event.values[2])
                hasGyroData = true
            }
            else -> return
        }
        
        // Only process when we have both sensors
        if (!hasAccelData || !hasGyroData) return
        
        // Create synchronized sample
        val sample = SensorSample(
            timestamp,
            latestAccelValues.first,
            latestAccelValues.second,
            latestAccelValues.third,
            latestGyroValues.first,
            latestGyroValues.second,
            latestGyroValues.third
        )
        
        // Handle calibration phase
        if (isCalibrating) {
            val elapsed = timestamp - calibrationStartTime
            
            // Only collect samples after a small initial delay to ensure stable readings
            if (elapsed > 50) {
                baselineSamples.add(sample)
            }
            
            // Finish calibration when both conditions are met:
            // 1. Minimum duration elapsed (after initial delay)
            // 2. Minimum number of samples collected
            if (elapsed >= BASELINE_DURATION_MS + 50 && baselineSamples.size >= MIN_BASELINE_SAMPLES) {
                // Calibration complete
                finishCalibration()
            } else if (elapsed > BASELINE_DURATION_MS * 2) {
                // Safety timeout: if we've waited too long, finish anyway
                Log.w(TAG, "Calibration timeout - collected ${baselineSamples.size} samples")
                finishCalibration()
            }
            return
        }
        
        // Gesture detection phase
        detectGesture(sample, timestamp)
    }
    
    private fun finishCalibration() {
        if (baselineSamples.isEmpty()) {
            Log.e(TAG, "No baseline samples collected - cannot start gesture detection")
            isCalibrating = false
            state = DetectionState.IDLE
            return
        }
        
        if (baselineSamples.size < MIN_BASELINE_SAMPLES) {
            Log.w(TAG, "Only collected ${baselineSamples.size} baseline samples (minimum: $MIN_BASELINE_SAMPLES) - continuing anyway")
        }
        
        // Calculate mean and standard deviation for each axis
        val accelXValues = baselineSamples.map { it.accelX }
        val accelYValues = baselineSamples.map { it.accelY }
        val accelZValues = baselineSamples.map { it.accelZ }
        val gyroXValues = baselineSamples.map { it.gyroX }
        val gyroYValues = baselineSamples.map { it.gyroY }
        val gyroZValues = baselineSamples.map { it.gyroZ }
        
        baselineMean = BaselineValues(
            accelXValues.average().toFloat(),
            accelYValues.average().toFloat(),
            accelZValues.average().toFloat(),
            gyroXValues.average().toFloat(),
            gyroYValues.average().toFloat(),
            gyroZValues.average().toFloat()
        )
        
        baselineStd = BaselineValues(
            calculateStdDev(accelXValues, baselineMean.accelX),
            calculateStdDev(accelYValues, baselineMean.accelY),
            calculateStdDev(accelZValues, baselineMean.accelZ),
            calculateStdDev(gyroXValues, baselineMean.gyroX),
            calculateStdDev(gyroYValues, baselineMean.gyroY),
            calculateStdDev(gyroZValues, baselineMean.gyroZ)
        )
        
        isCalibrating = false
        state = DetectionState.IDLE
        
        Log.d(TAG, "Baseline calibration complete:")
        Log.d(TAG, "  Accel: X=${String.format("%.4f", baselineMean.accelX)}+-${String.format("%.4f", baselineStd.accelX)}, Y=${String.format("%.4f", baselineMean.accelY)}+-${String.format("%.4f", baselineStd.accelY)}, Z=${String.format("%.4f", baselineMean.accelZ)}+-${String.format("%.4f", baselineStd.accelZ)}")
        Log.d(TAG, "  Gyro: X=${String.format("%.4f", baselineMean.gyroX)}+-${String.format("%.4f", baselineStd.gyroX)}, Y=${String.format("%.4f", baselineMean.gyroY)}+-${String.format("%.4f", baselineStd.gyroY)}, Z=${String.format("%.4f", baselineMean.gyroZ)}+-${String.format("%.4f", baselineStd.gyroZ)}")
    }
    
    private fun calculateStdDev(values: List<Float>, mean: Float): Float {
        if (values.isEmpty()) return 0f
        val variance = values.map { (it - mean) * (it - mean) }.average().toFloat()
        return kotlin.math.sqrt(variance)
    }
    
    private fun detectGesture(sample: SensorSample, timestamp: Long) {
        // Check cooldown
        if (state == DetectionState.COOLDOWN) {
            if (timestamp - lastDetectionTime >= GESTURE_COOLDOWN_MS) {
                state = DetectionState.IDLE
            } else {
                return
            }
        }
        
        when (state) {
            DetectionState.IDLE -> {
                // Check for threshold crossing
                val gyroXDeviation = sample.gyroX - baselineMean.gyroX
                val accelZDeviation = abs(sample.accelZ - baselineMean.accelZ)
                
                // Primary detector: gyro_x < baseline - threshold
                // Secondary detector: accel_z deviation > threshold
                if (gyroXDeviation < FLEXION_GYRO_X_THRESHOLD || accelZDeviation > FLEXION_ACCEL_Z_THRESHOLD) {
                    state = DetectionState.DETECTING
                    detectionStartTime = timestamp
                    peakTime = timestamp
                    peakGyroX = gyroXDeviation
                    peakAccelZ = accelZDeviation
                }
            }
            
            DetectionState.DETECTING -> {
                // Track peak
                val gyroXDeviation = sample.gyroX - baselineMean.gyroX
                val accelZDeviation = abs(sample.accelZ - baselineMean.accelZ)
                
                // Update peak if we find a larger deviation
                if (gyroXDeviation < peakGyroX) {
                    peakGyroX = gyroXDeviation
                    peakTime = timestamp
                }
                if (accelZDeviation > peakAccelZ) {
                    peakAccelZ = accelZDeviation
                    peakTime = timestamp
                }
                
                // Check for return to baseline (±2σ)
                val baselineBound = BASELINE_STD_MULTIPLIER
                val gyroXDev = sample.gyroX - baselineMean.gyroX
                val gyroXInBounds = abs(gyroXDev) <= baselineStd.gyroX * baselineBound
                val accelZDev = sample.accelZ - baselineMean.accelZ
                val accelZInBounds = abs(accelZDev) <= baselineStd.accelZ * baselineBound
                
                val elapsed = timestamp - detectionStartTime
                val peakToNow = timestamp - peakTime
                
                // Confirm gesture if:
                // 1. Peak detected (we have a significant deviation)
                // 2. Values return to baseline (±2σ)
                // 3. Duration is within acceptable range
                if ((gyroXInBounds || accelZInBounds) && elapsed >= FLEXION_MIN_DURATION_MS && elapsed <= FLEXION_MAX_DURATION_MS) {
                    // Gesture confirmed
                    state = DetectionState.CONFIRMED
                    confirmGesture(timestamp)
                } else if (elapsed > FLEXION_MAX_DURATION_MS) {
                    // Too long, reset
                    state = DetectionState.IDLE
                }
            }
            
            DetectionState.CONFIRMED -> {
                // Should not reach here, but handle gracefully
                state = DetectionState.IDLE
            }
            
            DetectionState.COOLDOWN -> {
                // Already handled above
            }
        }
    }
    
    private fun confirmGesture(timestamp: Long) {
        Log.d(TAG, "*** WRIST FLEXION DETECTED ***")
        Log.d(TAG, "  Peak: gyro_x=${String.format("%.4f", peakGyroX)}, accel_z=${String.format("%.4f", peakAccelZ)}, duration=${timestamp - detectionStartTime}ms")
        
        lastDetectionTime = timestamp
        state = DetectionState.COOLDOWN
        
        listener?.onGestureDetected(GestureType.WRIST_FLEXION)
    }
    
    fun stop() {
        isCalibrating = false
        state = DetectionState.IDLE
        baselineSamples.clear()
        Log.d(TAG, "Gesture detector stopped")
    }
}

