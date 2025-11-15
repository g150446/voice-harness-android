package com.g150446.shepherdsignal

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.util.Log
import kotlin.math.abs

enum class GestureType {
    WRIST_FLEXION,
    EXTERNAL_ROTATION
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
    
    // Tilt angle tracking for gravity compensation
    private var baselineTiltAngle: Float = 0f // Tilt angle during calibration (degrees)
    private var currentTiltAngle: Float = 0f // Current tilt angle (degrees)
    
    // Store latest sensor values for synchronization
    private var latestAccelValues = Triple(0f, 0f, 0f)
    private var latestGyroValues = Triple(0f, 0f, 0f)
    private var hasAccelData = false
    private var hasGyroData = false
    
    // Gesture count tracking for periodic recalibration
    private var gestureCount = 0
    
    companion object {
        // Wrist Flexion Thresholds (aggressively relaxed to detect very weak gestures)
        private const val FLEXION_GYRO_X_THRESHOLD = -1.2f // rad/s (negative = flexion) - further relaxed from -1.8 to detect very weak gestures
        private const val FLEXION_ACCEL_Z_THRESHOLD = 3.5f // m/s² deviation from baseline (gravity-compensated) - further relaxed from 4.5 to detect very weak gestures
        private const val FLEXION_MIN_DURATION_MS = 120L // 0.12s minimum gesture - further relaxed from 150ms for very fast weak gestures
        private const val FLEXION_MAX_DURATION_MS = 3000L // 3s maximum gesture
        private const val FLEXION_GYRO_Z_BASELINE_TOLERANCE = 0.8f // rad/s (gyro_z should be near baseline for flexion) - further relaxed from 0.7
        
        // Baseline Calibration
        private const val BASELINE_DURATION_MS = 800L // 0.8 seconds - optimized from analysis
        private const val MIN_BASELINE_SAMPLES = 20 // Minimum samples needed (at 50Hz, 20 samples = 400ms)
        private const val BASELINE_STD_MULTIPLIER = 2.5f // ±2.5σ for baseline bounds - relaxed from 2.0 for easier return detection
        
        // Cooldown to prevent false positives
        private const val GESTURE_COOLDOWN_MS = 500L
        
        // Periodic recalibration: recalibrate after every N gestures to handle baseline drift
        private const val RECALIBRATE_AFTER_GESTURES = 1 // Recalibrate after each gesture
        
        // Gravity constant (m/s²)
        private const val GRAVITY = 9.8f
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
        
        Log.d(TAG, "*** Starting baseline calibration ***")
        isCalibrating = true
        calibrationStartTime = System.currentTimeMillis()
        baselineSamples.clear()
        hasAccelData = false
        hasGyroData = false
        state = DetectionState.IDLE
        // Reset gesture count on fresh calibration
        gestureCount = 0
        Log.d(TAG, "Baseline calibration started (${BASELINE_DURATION_MS}ms, min ${MIN_BASELINE_SAMPLES} samples), state set to IDLE")
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
        
        // Calculate baseline tilt angle from baseline accelerometer values
        baselineTiltAngle = calculateTiltAngle(baselineMean.accelX, baselineMean.accelY, baselineMean.accelZ)
        
        isCalibrating = false
        state = DetectionState.IDLE
        
        // Calculate baseline gravity-compensated accel_z for reference
        val baselineGravityZ = calculateGravityZ(baselineTiltAngle)
        val baselineAccelZCompensated = baselineMean.accelZ - baselineGravityZ
        
        Log.d(TAG, "*** Baseline calibration complete - ready for gesture detection ***")
        Log.d(TAG, "  Samples collected: ${baselineSamples.size}")
        Log.d(TAG, "  Accel: X=${String.format("%.4f", baselineMean.accelX)}+-${String.format("%.4f", baselineStd.accelX)}, Y=${String.format("%.4f", baselineMean.accelY)}+-${String.format("%.4f", baselineStd.accelY)}, Z=${String.format("%.4f", baselineMean.accelZ)}+-${String.format("%.4f", baselineStd.accelZ)}")
        Log.d(TAG, "  Gyro: X=${String.format("%.4f", baselineMean.gyroX)}+-${String.format("%.4f", baselineStd.gyroX)}, Y=${String.format("%.4f", baselineMean.gyroY)}+-${String.format("%.4f", baselineStd.gyroY)}, Z=${String.format("%.4f", baselineMean.gyroZ)}+-${String.format("%.4f", baselineStd.gyroZ)}")
        Log.d(TAG, "  Baseline Tilt Angle: ${String.format("%.2f", baselineTiltAngle)}°")
        Log.d(TAG, "  Baseline Gravity Z: ${String.format("%.4f", baselineGravityZ)} m/s², Accel Z (compensated): ${String.format("%.4f", baselineAccelZCompensated)} m/s²")
        Log.d(TAG, "  State set to IDLE, isCalibrating=false")
    }
    
    private fun calculateStdDev(values: List<Float>, mean: Float): Float {
        if (values.isEmpty()) return 0f
        val variance = values.map { (it - mean) * (it - mean) }.average().toFloat()
        return kotlin.math.sqrt(variance)
    }
    
    /**
     * Calculate tilt angle between watch face normal (Z-axis) and vertical (gravity direction).
     * Uses Method 2: full accelerometer vector magnitude for accurate calculation.
     * 
     * @param accelX Accelerometer X value
     * @param accelY Accelerometer Y value
     * @param accelZ Accelerometer Z value
     * @return Tilt angle in degrees (0° = horizontal face up, 90° = vertical)
     */
    private fun calculateTiltAngle(accelX: Float, accelY: Float, accelZ: Float): Float {
        val accelMagnitude = kotlin.math.sqrt(accelX * accelX + accelY * accelY + accelZ * accelZ)
        if (accelMagnitude < 0.1f) return 0f // Avoid division by zero
        
        // Calculate angle between Z-axis (perpendicular to watch face) and vertical (gravity)
        val angleRadians = kotlin.math.acos(accelZ / accelMagnitude)
        return Math.toDegrees(angleRadians.toDouble()).toFloat()
    }
    
    /**
     * Calculate gravity component on Z-axis accelerometer based on tilt angle.
     * 
     * Formula: gravity_z = 9.8 * cos(tilt_angle)
     * - 0° (horizontal): gravity_z = 9.8 m/s² (full gravity)
     * - 45°: gravity_z = 4.9 m/s² (half gravity)
     * - 90° (vertical): gravity_z = 0 m/s² (no gravity on Z-axis)
     * 
     * @param tiltAngleDegrees Tilt angle in degrees
     * @return Gravity component on Z-axis in m/s²
     */
    private fun calculateGravityZ(tiltAngleDegrees: Float): Float {
        val tiltAngleRadians = Math.toRadians(tiltAngleDegrees.toDouble())
        return GRAVITY * kotlin.math.cos(tiltAngleRadians).toFloat()
    }
    
    private fun detectGesture(sample: SensorSample, timestamp: Long) {
        // Check cooldown
        if (state == DetectionState.COOLDOWN) {
            val cooldownElapsed = timestamp - lastDetectionTime
            if (cooldownElapsed >= GESTURE_COOLDOWN_MS) {
                state = DetectionState.IDLE
            } else {
                return
            }
        }
        
        when (state) {
            DetectionState.IDLE -> {
                // Calculate deviations from baseline
                val gyroXDeviation = sample.gyroX - baselineMean.gyroX
                val gyroZDeviation = sample.gyroZ - baselineMean.gyroZ
                
                // Calculate current tilt angle for gravity compensation
                currentTiltAngle = calculateTiltAngle(sample.accelX, sample.accelY, sample.accelZ)
                
                // Calculate gravity-compensated accelerometer Z values
                val currentGravityZ = calculateGravityZ(currentTiltAngle)
                val baselineGravityZ = calculateGravityZ(baselineTiltAngle)
                
                val accelZCompensated = sample.accelZ - currentGravityZ
                val baselineAccelZCompensated = baselineMean.accelZ - baselineGravityZ
                val accelZDeviation = abs(accelZCompensated - baselineAccelZCompensated)
                
                // Improved detection algorithm based on data analysis:
                // 1. Primary filter: gyro_z must be near baseline (filters out negative samples)
                //    Negative samples have 2.9x more gyro_z activity (mean abs = 0.68 vs 0.24)
                // 2. Primary detector: gyro_x < -2.5 rad/s (wrist flexion)
                // 3. Secondary detector: accel_z (gravity-compensated) > 6.0 m/s² (backup confirmation)
                
                val gyroZNearBaseline = abs(gyroZDeviation) <= FLEXION_GYRO_Z_BASELINE_TOLERANCE
                
                // Primary flexion detector: gyro_x threshold with gyro_z constraint
                val flexionDetected = gyroXDeviation < FLEXION_GYRO_X_THRESHOLD && gyroZNearBaseline
                
                // Secondary flexion detector: gravity-compensated accel_z threshold with gyro_z constraint
                val flexionDetectedSecondary = accelZDeviation > FLEXION_ACCEL_Z_THRESHOLD && gyroZNearBaseline
                
                if (flexionDetected || flexionDetectedSecondary) {
                    val detectorType = if (flexionDetected) "PRIMARY (gyroX)" else "SECONDARY (accelZ)"
                    Log.d(TAG, "Flexion threshold crossed via $detectorType: gyroXDev=${String.format("%.3f", gyroXDeviation)}, accelZDev=${String.format("%.3f", accelZDeviation)}, transitioning to DETECTING")
                    startDetecting(sample, timestamp)
                }
            }
            
            DetectionState.DETECTING -> {
                val elapsed = timestamp - detectionStartTime
                val gyroXDeviation = sample.gyroX - baselineMean.gyroX
                val gyroZDeviation = sample.gyroZ - baselineMean.gyroZ
                
                // Calculate current tilt angle for gravity compensation
                currentTiltAngle = calculateTiltAngle(sample.accelX, sample.accelY, sample.accelZ)
                
                // Calculate gravity-compensated accelerometer Z values
                val currentGravityZ = calculateGravityZ(currentTiltAngle)
                val baselineGravityZ = calculateGravityZ(baselineTiltAngle)
                
                val accelZCompensated = sample.accelZ - currentGravityZ
                val baselineAccelZCompensated = baselineMean.accelZ - baselineGravityZ
                val accelZDeviation = abs(accelZCompensated - baselineAccelZCompensated)
                
                // Update flexion peaks (using gravity-compensated values)
                if (gyroXDeviation < peakGyroX) {
                    peakGyroX = gyroXDeviation
                    peakTime = timestamp
                }
                if (accelZDeviation > peakAccelZ) {
                    peakAccelZ = accelZDeviation
                    peakTime = timestamp
                }
                
                // Check for return to baseline (using gravity-compensated values)
                val baselineBound = BASELINE_STD_MULTIPLIER
                val gyroXDev = sample.gyroX - baselineMean.gyroX
                val gyroXInBounds = abs(gyroXDev) <= baselineStd.gyroX * baselineBound
                
                // Use gravity-compensated accel_z for baseline check
                val accelZDev = accelZCompensated - baselineAccelZCompensated
                val accelZInBounds = abs(accelZDev) <= baselineStd.accelZ * baselineBound
                
                // Verify still flexion (gyro_z near baseline)
                val gyroZNearBaseline = abs(gyroZDeviation) <= FLEXION_GYRO_Z_BASELINE_TOLERANCE
                
                // Confirm if returned to baseline and duration valid
                if ((gyroXInBounds || accelZInBounds) && gyroZNearBaseline && 
                    elapsed >= FLEXION_MIN_DURATION_MS && elapsed <= FLEXION_MAX_DURATION_MS) {
                    Log.d(TAG, "Gesture confirmed: returned to baseline, duration=${elapsed}ms, transitioning to CONFIRMED")
                    state = DetectionState.CONFIRMED
                    confirmGesture(timestamp)
                } else if (elapsed > FLEXION_MAX_DURATION_MS) {
                    // Too long, reset
                    Log.w(TAG, "Gesture too long (${elapsed}ms), resetting to IDLE")
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
    
    private fun startDetecting(sample: SensorSample, timestamp: Long) {
        state = DetectionState.DETECTING
        detectionStartTime = timestamp
        peakTime = timestamp
        
        val gyroXDeviation = sample.gyroX - baselineMean.gyroX
        val accelZDeviation = abs(sample.accelZ - baselineMean.accelZ)
        
        peakGyroX = gyroXDeviation
        peakAccelZ = accelZDeviation
    }
    
    private fun confirmGesture(timestamp: Long) {
        val duration = timestamp - detectionStartTime
        
        Log.d(TAG, "*** WRIST FLEXION DETECTED ***")
        Log.d(TAG, "  Peak: gyro_x=${String.format("%.4f", peakGyroX)}, accel_z=${String.format("%.4f", peakAccelZ)}, duration=${duration}ms")
        Log.d(TAG, "  Gesture count before increment: $gestureCount")
        
        lastDetectionTime = timestamp
        state = DetectionState.COOLDOWN
        Log.d(TAG, "State set to COOLDOWN, lastDetectionTime=$lastDetectionTime")
        
        // Notify listener first
        if (listener != null) {
            Log.d(TAG, "Notifying listener of gesture detection")
            listener?.onGestureDetected(GestureType.WRIST_FLEXION)
        } else {
            Log.e(TAG, "ERROR: Listener is null! Gesture will not be processed.")
        }
        
        // Increment gesture count and check if recalibration is needed
        gestureCount++
        Log.d(TAG, "Gesture count after increment: $gestureCount (threshold: $RECALIBRATE_AFTER_GESTURES)")
        if (gestureCount >= RECALIBRATE_AFTER_GESTURES) {
            Log.d(TAG, "*** Recalibrating baseline after $gestureCount gesture(s) to handle baseline drift ***")
            gestureCount = 0 // Reset counter
            // Start recalibration - it will happen during the cooldown period and extend it slightly
            // The startCalibration() method has built-in checks to prevent duplicate calibrations
            startCalibration()
        } else {
            Log.d(TAG, "No recalibration needed yet (count: $gestureCount < threshold: $RECALIBRATE_AFTER_GESTURES)")
        }
    }
    
    fun stop() {
        isCalibrating = false
        state = DetectionState.IDLE
        baselineSamples.clear()
        gestureCount = 0 // Reset gesture count
        Log.d(TAG, "Gesture detector stopped")
    }
}

