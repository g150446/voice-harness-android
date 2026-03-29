# Gesture Detection Specification

## Overview

The Harness Voice gesture detection system uses threshold-based algorithms to recognize wrist gestures from accelerometer and gyroscope sensor data. This specification defines the detection algorithms, state machine, and thresholds used for real-time gesture recognition.

## Supported Gestures

### 1. Wrist Flexion
- **Description**: Forward wrist bend motion
- **Primary Use**: Toggle voice recording, scroll results
- **Detection Method**: Gyroscope X-axis rotation with accelerometer Z-axis confirmation

### 2. External Rotation
- **Description**: Outward wrist rotation motion
- **Primary Use**: Show close confirmation dialog
- **Detection Method**: Gyroscope Z-axis rotation with cross-axis verification

## Detection Algorithm

### State Machine

```
IDLE → DETECTING → CONFIRMED → COOLDOWN → IDLE
  ↑                    ↓
  └────────────────────┘
       (timeout)
```

#### States

1. **IDLE**
   - Baseline monitoring active
   - Waiting for threshold crossing
   - Periodic baseline recalibration

2. **DETECTING**
   - Threshold exceeded
   - Tracking peak values
   - Monitoring for return to baseline
   - Time window: 0.3s - 3.0s

3. **CONFIRMED**
   - Peak detected
   - Return to baseline within time window
   - Gesture type determined
   - Listener callback triggered

4. **COOLDOWN**
   - Duration: 500ms standard, 500ms extended after rotation
   - Prevents false positives
   - Suppresses multiple detections from single gesture

### Detection Flow

```
Sensor Event (50Hz)
    ↓
Baseline Calibration Check
    ↓
IDLE State: Threshold Check
    ↓ (threshold exceeded)
DETECTING State: Track Peak
    ↓
Wait for Return to Baseline
    ↓ (within time window)
Gesture Type Classification
    ↓
CONFIRMED State: Trigger Callback
    ↓
COOLDOWN State
    ↓
Return to IDLE
```

## Thresholds and Parameters

### Wrist Flexion Thresholds

| Parameter | Value | Unit | Description |
|-----------|-------|------|-------------|
| `FLEXION_GYRO_X_THRESHOLD` | -4.0 | rad/s | Primary detector: negative = flexion |
| `FLEXION_ACCEL_Z_THRESHOLD` | 8.0 | m/s² | Secondary confirmation (gravity-compensated) |
| `FLEXION_MIN_DURATION_MS` | 300 | ms | Minimum gesture duration |
| `FLEXION_MAX_DURATION_MS` | 3000 | ms | Maximum gesture duration |
| `FLEXION_GYRO_Z_BASELINE_TOLERANCE` | 0.8 | rad/s | Gyro Z must be near baseline |

### External Rotation Thresholds

| Parameter | Value | Unit | Description |
|-----------|-------|------|-------------|
| `ROTATION_GYRO_Z_THRESHOLD` | 1.2 | rad/s | Primary detector |
| `ROTATION_GYRO_X_VERIFICATION` | Not strongly negative | - | Cross-axis verification |
| `ROTATION_MIN_DURATION_MS` | 300 | ms | Minimum gesture duration |
| `ROTATION_MAX_DURATION_MS` | 3000 | ms | Maximum gesture duration |

### Baseline Calibration

| Parameter | Value | Unit | Description |
|-----------|-------|------|-------------|
| `BASELINE_DURATION_MS` | 800 | ms | Calibration duration |
| `MIN_BASELINE_SAMPLES` | 20 | samples | Minimum samples needed |
| `BASELINE_STD_MULTIPLIER` | 2.5 | σ | Baseline bounds (±2.5σ) |
| `RECALIBRATE_AFTER_GESTURES` | 1 | gestures | Recalibrate frequency |

### Cooldown Parameters

| Parameter | Value | Unit | Description |
|-----------|-------|------|-------------|
| `GESTURE_COOLDOWN_MS` | 500 | ms | Standard cooldown |
| `ROTATION_COOLDOWN_MS` | 500 | ms | Extended after rotation |

### Conflict Resolution

| Parameter | Value | Unit | Description |
|-----------|-------|------|-------------|
| `ROTATION_CHECK_DURATION_MS` | 250 | ms | Analysis window |
| `ROTATION_CUMULATIVE_GYRO_X_THRESHOLD` | 50.0 | rad/s | Cumulative threshold |
| `ROTATION_PEAK_GYRO_X_THRESHOLD` | 8.0 | rad/s | Peak threshold |
| `SUPPRESS_PEAK_GYRO_X_THRESHOLD` | 10.0 | rad/s | Suppression threshold |

## Baseline Calibration System

### Initial Calibration
1. **Duration**: 800ms at startup
2. **Sample Collection**: Accelerometer and gyroscope values
3. **Statistics Calculated**: Mean and standard deviation for all axes
4. **Tilt Compensation**: Baseline tilt angle calculated from accelerometer

### Periodic Recalibration
- **Trigger**: After each gesture detection
- **Timing**: During cooldown period
- **Purpose**: Handle baseline drift from wrist position changes
- **Method**: Same as initial calibration

### Gravity Compensation
- **Input**: Accelerometer X, Y, Z values
- **Calculation**: Tilt angle = atan2(accel_y, accel_z)
- **Compensation**: Subtract gravity component from Z-axis
- **Formula**: `compensated_accel_z = accel_z - (GRAVITY * cos(tilt_angle))`

## Gesture Type Classification

### Dual Detection System
The system can detect both gesture types simultaneously and uses conflict resolution:

1. **Threshold Monitoring**: Both flexion and rotation thresholds checked on every sensor event
2. **Conflict Detection**: If both thresholds crossed simultaneously
3. **Signal Strength Comparison**: Stronger signal wins
4. **Cross-Axis Verification**: Additional checks prevent false positives

### Flexion vs Rotation Distinction

**Primary Discriminator**: Cumulative Gyro X over 500ms
- **Wrist Flexion**: < 50 rad/s (typically 2-3 rad/s)
- **External Rotation**: > 50 rad/s (typically 108-130 rad/s)
- **Safety Margin**: 33x separation

**Secondary Verification**:
- Flexion: Gyro Z near baseline, Gyro X strongly negative
- Rotation: Gyro Z positive, Gyro X not strongly negative

## Sensor Configuration

### Sensor Types
- **Accelerometer**: TYPE_ACCELEROMETER
- **Gyroscope**: TYPE_GYROSCOPE

### Sampling Rate
- **Setting**: SENSOR_DELAY_FASTEST
- **Effective Rate**: ~50Hz
- **Purpose**: High-frequency data for responsive detection

### Data Synchronization
- Latest accelerometer and gyroscope values stored separately
- Both sensors must provide data before processing begins
- Prevents processing with stale or missing data

## Detection Modes

### 1. GESTURE_CONTROL (Production)
- **Purpose**: Real-time gesture control of voice recording
- **Behavior**:
  - Flexion toggles recording
  - Rotation shows close confirmation
  - Result display: flexion scrolls content
- **Data Logging**: Only to logcat

### 2. GESTURE_TEST
- **Purpose**: Real-time gesture testing and validation
- **Behavior**: Shows detected gesture type on screen
- **Display**: "Waiting Gesture" → "Wrist Flexion" or "External Rotation"
- **Analysis Period**: 0.5s for gesture classification
- **Data Logging**: Comprehensive sensor data to logcat

### 3. FLEXION_DATA_COLLECTION
- **Purpose**: Collect wrist flexion gesture samples
- **Output**: CSV files in `gesture_data/flexion/`
- **Automatic**: File naming with timestamp
- **Data Logging**: Full sensor data to CSV and logcat

### 4. NEGATIVE_SAMPLES_COLLECTION
- **Purpose**: Collect non-gesture movement samples
- **Output**: CSV files in `gesture_data/negative_samples/`
- **Automatic**: File naming with timestamp
- **Data Logging**: Full sensor data to CSV and logcat

## Performance Characteristics

### Signal-to-Noise Ratio
- **Flexion Gyro X**: 152-539x SNR
- **Rotation Gyro Z**: Similar high SNR
- **Conclusion**: Threshold-based approach is sufficient, no ML required

### Response Time
- **Detection Latency**: < 20ms (one sensor cycle at 50Hz)
- **Minimum Gesture Duration**: 300ms
- **Typical Gesture Duration**: 500-1000ms

### Reliability
- **False Positive Prevention**: Cooldown period + baseline verification
- **False Negative Prevention**: Relaxed thresholds + periodic recalibration
- **Baseline Drift Handling**: Automatic recalibration after each gesture

## API Interface

### GestureDetector Class

```kotlin
class GestureDetector(private val listener: GestureDetectionListener?)

interface GestureDetectionListener {
    fun onGestureDetected(type: GestureType)
}

enum class GestureType {
    FLEXION,
    ROTATION
}

enum class DetectionState {
    IDLE,
    DETECTING,
    CONFIRMED,
    COOLDOWN
}
```

### Key Methods

```kotlin
fun processSensorEvent(event: SensorEvent)
fun reset()
fun getState(): DetectionState
```

### Data Structures

```kotlin
data class BaselineValues(
    var accelX: Float,
    var accelY: Float,
    var accelZ: Float,
    var gyroX: Float,
    var gyroY: Float,
    var gyroZ: Float
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
```

## Testing and Validation

### Test Mode Usage
1. Change `sensorMode` to `GESTURE_TEST` in MainActivity.kt
2. Deploy to watch
3. Perform gestures
4. Observe displayed gesture type
5. Check logcat for detailed sensor analysis

### Data Collection
1. Change `sensorMode` to `FLEXION_DATA_COLLECTION` or `NEGATIVE_SAMPLES_COLLECTION`
2. Deploy to watch
3. Perform gestures or movements
4. CSV files automatically saved to watch storage
5. Transfer files to computer for analysis

### Threshold Tuning
1. Collect data samples
2. Analyze CSV files for peak values and noise levels
3. Adjust thresholds in `GestureDetector.kt` companion object
4. Test with new thresholds
5. Iterate until optimal performance

## Future Enhancements

### Planned Features
- Gesture sensitivity customization (user-adjustable thresholds)
- Multiple gesture pattern recognition (sequences)
- Gesture-based shortcuts (different actions for different gestures)
- Advanced conflict resolution algorithms

### Research Areas
- Machine learning for complex gesture recognition
- User-specific calibration and adaptation
- Additional gesture types (pronation, supination, etc.)
- Gesture velocity and acceleration analysis
