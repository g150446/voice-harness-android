# Sensor Data Format Specification

## Overview

This specification defines the sensor data formats used throughout Harness Voice for gesture detection, logging, and analysis. It covers real-time sensor events, CSV logging format, and data collection procedures.

## Sensor Types

### Accelerometer (TYPE_ACCELEROMETER)

**Measures**: Linear acceleration along three axes (m/s²)

**Coordinate System**: Device coordinate system
- **X-axis**: Horizontal, positive to the right
- **Y-axis**: Vertical, positive up
- **Z-axis**: Perpendicular to screen, positive outward

**Includes**: Gravity component (9.8 m/s²)

**Sample Values**:
```
At rest (screen up): X≈0, Y≈0, Z≈9.8
Tilted right: X>0, Y≈0, Z<9.8
Tilted forward: X≈0, Y>0, Z<9.8
```

### Gyroscope (TYPE_GYROSCOPE)

**Measures**: Angular velocity around three axes (rad/s)

**Coordinate System**: Same as accelerometer
- **X-axis**: Rotation around horizontal axis (pitch)
- **Y-axis**: Rotation around vertical axis (yaw)
- **Z-axis**: Rotation around perpendicular axis (roll)

**Direction**: Right-hand rule (counterclockwise = positive)

**Sample Values**:
```
Stationary: X≈0, Y≈0, Z≈0
Wrist flexion: X<0 (negative), Y≈0, Z≈0
External rotation: X≈0, Y≈0, Z>0 (positive)
```

## Real-Time Sensor Events

### SensorEvent Structure

**Android SensorEvent**:
```kotlin
class SensorEvent {
    val sensor: Sensor           // Sensor type
    val accuracy: Int            // Accuracy level
    val timestamp: Long          // Nanoseconds since boot
    val values: FloatArray       // Sensor values [x, y, z]
}
```

### Event Processing

**Sampling Rate**: SENSOR_DELAY_FASTEST (~50Hz)

**Event Flow**:
```
Sensor Hardware (50Hz)
    ↓
SensorManager
    ↓
SensorEventListener.onSensorChanged()
    ↓
SensorDataLogger (mode-dependent processing)
    ↓
GestureDetector (in GESTURE_CONTROL/TEST modes)
```

### Data Synchronization

**Storage**:
```kotlin
// Latest values from each sensor
private var latestAccelValues = Triple(0f, 0f, 0f)
private var latestGyroValues = Triple(0f, 0f, 0f)
private var hasAccelData = false
private var hasGyroData = false
```

**Processing**:
```kotlin
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

// Only process when both sensors have data
if (hasAccelData && hasGyroData) {
    gestureDetector?.processSensorEvent(event)
}
```

## CSV Logging Format

### File Naming Convention

**Pattern**: `gesture_YYYYMMDD_HHmmss_SSS.csv`

**Example**: `gesture_20250130_143022_456.csv`

**Components**:
- `gesture_`: Prefix
- `YYYYMMDD`: Date (e.g., 20250130)
- `HHmmss`: Time (e.g., 143022)
- `SSS`: Milliseconds (e.g., 456)
- `.csv`: Extension

### Directory Structure

```
/storage/emulated/0/Android/data/com.g150446.harnessvoice/files/
├── gesture_data/
│   ├── flexion/
│   │   ├── gesture_20250130_143022_456.csv
│   │   ├── gesture_20250130_143125_789.csv
│   │   └── ...
│   └── negative_samples/
│       ├── gesture_20250130_144530_123.csv
│       ├── gesture_20250130_144622_987.csv
│       └── ...
```

### CSV Column Format

**Header Row**:
```csv
timestamp_ms,accel_x,accel_y,accel_z,gyro_x,gyro_y,gyro_z
```

**Column Definitions**:

| Column | Type | Unit | Description |
|--------|------|------|-------------|
| `timestamp_ms` | Long | ms | Milliseconds since epoch |
| `accel_x` | Float | m/s² | Acceleration X-axis |
| `accel_y` | Float | m/s² | Acceleration Y-axis |
| `accel_z` | Float | m/s² | Acceleration Z-axis |
| `gyro_x` | Float | rad/s | Angular velocity X-axis |
| `gyro_y` | Float | rad/s | Angular velocity Y-axis |
| `gyro_z` | Float | rad/s | Angular velocity Z-axis |

**Sample Data**:
```csv
timestamp_ms,accel_x,accel_y,accel_z,gyro_x,gyro_y,gyro_z
1706626822456,0.245,-0.123,9.801,0.012,0.008,-0.015
1706626822476,0.248,-0.121,9.798,0.015,0.010,-0.012
1706626822496,0.251,-0.119,9.795,0.018,0.012,-0.010
1706626822516,-2.345,1.234,12.456,-4.567,0.234,-0.123
1706626822536,-5.678,3.456,15.789,-8.901,0.456,-0.234
```

### CSV Writing Implementation

```kotlin
private fun writeToCsv(accelX: Float, accelY: Float, accelZ: Float,
                       gyroX: Float, gyroY: Float, gyroZ: Float) {
    val timestamp = System.currentTimeMillis()
    val line = "$timestamp,$accelX,$accelY,$accelZ,$gyroX,$gyroY,$gyroZ\n"

    try {
        csvWriter?.write(line)
        csvWriter?.flush()
    } catch (e: IOException) {
        Log.e(TAG, "Error writing to CSV", e)
    }
}
```

## Data Collection Modes

### FLEXION_DATA_COLLECTION

**Purpose**: Collect wrist flexion gesture samples

**Output**: CSV files in `gesture_data/flexion/`

**Procedure**:
1. Deploy app with mode set to `FLEXION_DATA_COLLECTION`
2. Launch watch app
3. Wait for baseline calibration (800ms)
4. Perform wrist flexion gesture
5. CSV file automatically created and data logged
6. Repeat for multiple samples
7. Transfer files to computer for analysis

**Logged Data**:
- All accelerometer and gyroscope values
- Timestamp for each sample
- Continuous logging during app runtime

### NEGATIVE_SAMPLES_COLLECTION

**Purpose**: Collect non-gesture movement samples

**Output**: CSV files in `gesture_data/negative_samples/`

**Procedure**:
1. Deploy app with mode set to `NEGATIVE_SAMPLES_COLLECTION`
2. Launch watch app
3. Wait for baseline calibration (800ms)
4. Perform various non-gesture movements:
   - Walking
   - Arm swinging
   - Typing
   - Random wrist movements
5. CSV file automatically created and data logged
6. Transfer files to computer for analysis

**Purpose of Negative Samples**:
- Identify false positive triggers
- Tune thresholds to avoid unwanted detections
- Validate gesture distinction algorithms

## Internal Data Structures

### SensorSample

```kotlin
data class SensorSample(
    val timestamp: Long,    // Milliseconds since epoch
    val accelX: Float,      // m/s²
    val accelY: Float,      // m/s²
    val accelZ: Float,      // m/s²
    val gyroX: Float,       // rad/s
    val gyroY: Float,       // rad/s
    val gyroZ: Float        // rad/s
)
```

**Usage**: Baseline calibration sample collection

**Storage**: `baselineSamples` list during calibration

### BaselineValues

```kotlin
data class BaselineValues(
    var accelX: Float = 0f,     // Mean or std dev (m/s²)
    var accelY: Float = 0f,
    var accelZ: Float = 0f,
    var gyroX: Float = 0f,      // Mean or std dev (rad/s)
    var gyroY: Float = 0f,
    var gyroZ: Float = 0f
)
```

**Instances**:
- `baselineMean`: Mean values during calibration
- `baselineStd`: Standard deviation during calibration

**Calculation**:
```kotlin
// Calculate mean
baselineMean.accelX = samples.map { it.accelX }.average().toFloat()

// Calculate standard deviation
val variance = samples.map { (it.accelX - mean) * (it.accelX - mean) }.average()
baselineStd.accelX = sqrt(variance).toFloat()
```

## Logcat Logging Format

### Standard Log Messages

**Format**: `[TAG] Message: value1, value2, ...`

**Examples**:
```
D/GestureDetector: Baseline calibrated - accel: (0.24, -0.12, 9.80), gyro: (0.01, 0.01, -0.02)
D/GestureDetector: DETECTING: gyro_x=-4.23 exceeds threshold=-4.0
D/GestureDetector: Peak detected - gyro_x=-8.45, accel_z=12.34
D/GestureDetector: CONFIRMED: Wrist flexion detected
D/SensorDataLogger: CSV logging started: gesture_20250130_143022_456.csv
```

### Gesture Analysis Logging

**Comprehensive Analysis** (in GESTURE_TEST mode):
```
D/GestureDetector: ====== Gesture Analysis ======
D/GestureDetector: Cumulative Gyro X: 2.34 rad/s
D/GestureDetector: Cumulative Gyro Y: 0.56 rad/s
D/GestureDetector: Cumulative Gyro Z: 0.12 rad/s
D/GestureDetector: Peak Gyro X: 0.89 rad/s
D/GestureDetector: Peak Gyro Y: 0.23 rad/s
D/GestureDetector: Peak Gyro Z: 1.45 rad/s
D/GestureDetector: Cumulative Accel X: 1.23 m/s²
D/GestureDetector: Cumulative Accel Y: 2.34 m/s²
D/GestureDetector: Cumulative Accel Z: 15.67 m/s²
D/GestureDetector: Peak Accel X: 2.45 m/s²
D/GestureDetector: Peak Accel Y: 3.56 m/s²
D/GestureDetector: Peak Accel Z: 18.90 m/s²
D/GestureDetector: ============================
D/GestureDetector: Determined gesture: FLEXION
```

## Data Analysis

### Extracting Data from Watch

**ADB Method**:
```bash
# List files
adb -s <watch-serial> shell ls /storage/emulated/0/Android/data/com.g150446.harnessvoice/files/gesture_data/flexion/

# Pull all files
adb -s <watch-serial> pull /storage/emulated/0/Android/data/com.g150446.harnessvoice/files/gesture_data/ ./local_analysis/
```

**File Transfer**:
- Connect watch to computer via Bluetooth
- Use Android File Transfer or similar tool
- Navigate to app data directory
- Copy CSV files to local storage

### Analysis Tools

**Python (Pandas)**:
```python
import pandas as pd
import matplotlib.pyplot as plt

# Load CSV
df = pd.read_csv('gesture_20250130_143022_456.csv')

# Plot gyro_x over time
plt.plot(df['timestamp_ms'], df['gyro_x'])
plt.xlabel('Time (ms)')
plt.ylabel('Gyro X (rad/s)')
plt.title('Wrist Flexion - Gyro X')
plt.show()

# Calculate peak values
peak_gyro_x = df['gyro_x'].min()  # Negative peak
peak_accel_z = df['accel_z'].max()

print(f"Peak Gyro X: {peak_gyro_x} rad/s")
print(f"Peak Accel Z: {peak_accel_z} m/s²")
```

**Excel/Google Sheets**:
- Import CSV file
- Create time-series charts
- Calculate statistics (mean, max, min, std dev)
- Compare gesture samples

### Threshold Tuning

**Process**:
1. Collect 10-20 samples of target gesture
2. Collect 10-20 negative samples
3. Analyze peak values and noise levels
4. Calculate signal-to-noise ratio
5. Set threshold between signal and noise
6. Add safety margin (typically 2-3x noise level)
7. Test with new threshold
8. Iterate until optimal performance

**Example Analysis**:
```
Wrist Flexion Gyro X Analysis:
- Gesture peak: -8.5 to -12.3 rad/s
- Noise peak: -0.5 to -1.0 rad/s
- SNR: 8.5x to 24.6x
- Threshold: -4.0 rad/s (4x noise, 50% of signal)
- Result: High detection rate, no false positives
```

## Data Storage and Retention

### Storage Location

**Path**: `/storage/emulated/0/Android/data/com.g150446.harnessvoice/files/`

**Type**: External storage (app-specific directory)

**Permissions**: No special permissions required (scoped storage)

**Accessibility**:
- App can read/write freely
- ADB can access with appropriate permissions
- Deleted when app is uninstalled

### File Lifecycle

**Creation**: Automatic on first sensor event in data collection mode

**Growth**: Continuous appending during app runtime

**Size**: Depends on duration (typical: 50KB per minute at 50Hz)

**Deletion**: Manual by user or app uninstall

### Storage Management

**Recommendations**:
- Periodically transfer files to computer
- Delete old files to save space
- Monitor storage usage (CSV files grow quickly)
- Use data collection modes sparingly

**Typical File Sizes**:
- 1 minute: ~50 KB
- 10 minutes: ~500 KB
- 1 hour: ~3 MB

## Data Privacy

### Personal Data

**Contains**: Only sensor readings (acceleration, angular velocity)

**Does NOT Contain**:
- GPS location
- User identity
- Voice recordings
- Network data
- Other personal information

### Data Sharing

**Local Only**: CSV files stored locally on watch

**No Transmission**: Data not sent to external servers

**User Control**: User must manually transfer files

### Best Practices

- Delete collected data after analysis
- Don't share CSV files publicly (contains device-specific patterns)
- Use data only for gesture algorithm development

## Future Enhancements

### Planned Features
- Automated data export to phone
- Cloud backup of gesture samples
- Real-time data visualization on watch
- Compressed data format (binary instead of CSV)

### Advanced Analysis
- Machine learning feature extraction
- Gesture pattern clustering
- User-specific gesture profiling
- Anomaly detection for data quality
