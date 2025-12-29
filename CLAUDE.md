# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Harness Voice** is an Android application for Wear OS that enables wrist gesture detection to control voice recording. The app uses sensor-based gesture recognition to toggle recording, providing hands-free control of voice transcription features.

**Migration Context**: This project originated as **RingXwatch** (ring tap gesture control) and was migrated to **Harness Voice** (wrist gesture detection). Some ring gesture-related code remains for reference and reusable components.

## Build Commands

### Building the Project
```bash
# Build phone app
./gradlew :app:assembleDebug

# Build wear app
./gradlew :wear:assembleDebug

# Build both modules
./gradlew assembleDebug
```

### Installing
```bash
# Install phone app
./gradlew :app:installDebug

# Install wear app
./gradlew :wear:installDebug

# Install both
./gradlew installDebug
```

### Cleaning
```bash
./gradlew clean
```

### Running Tests
```bash
# Run all tests
./gradlew test

# Run tests for specific module
./gradlew :app:test
./gradlew :wear:test
```

## Architecture

### Dual-Module Structure

This is a **multi-module Android project** with two distinct apps:

1. **Phone App** (`app/` module)
   - Package: `com.g150446.harnessvoice`
   - Min SDK: 24, Target SDK: 36
   - Java compatibility: VERSION_1_8
   - **Purpose**: Remote control and configuration for the watch app

2. **Wear OS App** (`wear/` module)
   - Package: `com.g150446.harnessvoice`
   - Min SDK: 30, Target SDK: 36
   - Java compatibility: VERSION_11
   - **Purpose**: Main gesture detection and voice recording functionality

**IMPORTANT**: These are separate applications with different capabilities and SDK requirements. Changes to one module do not affect the other unless you modify shared communication protocols.

### Phone App Architecture (`app/`)

**Key Components:**
- `MainActivity.kt` - Jetpack Compose UI with:
  - Watch app launch controls
  - Groq API settings button
  - Debug message display
  - Uses Wearable Data Layer API for phone-to-watch communication
- `GroqSettingsActivity.kt` - Configure and sync Groq API key to watch
- `DebugMessageManager.kt` - Debug message flow manager (Kotlin Flow)
- `BootReceiver.kt` - Boot receiver (currently inactive)
- Legacy components (RingDeviceAdminReceiver, RingAccessibilityService, RingWorker) - preserved from RingXwatch migration

**Communication Protocol:**
- Sends messages to watch via `/launch_app` path
- Uses `MessageClient` and `NodeClient` from Wearable Data Layer API

### Wear OS App Architecture (`wear/`)

**Core Components:**

1. **`presentation/MainActivity.kt`** - Main activity with multiple responsibilities:
   - Voice recording via MediaRecorder
   - Groq API integration (Whisper transcription + GPT chat completions)
   - Gesture detection integration
   - Screen state management (wake locks, ambient mode prevention)
   - Result display with gesture-based scrolling
   - Permission handling (RECORD_AUDIO)

2. **`GestureDetector.kt`** - Threshold-based dual gesture recognition:
   - **State Machine**: IDLE → DETECTING → CONFIRMED → COOLDOWN
   - **Wrist Flexion Detection**:
     - Primary: gyro_x < -4.0 rad/s (negative = flexion)
     - Secondary: accel_z > 8.0 m/s² deviation (gravity-compensated)
     - Peak-then-return pattern within 0.3-3.0s window
   - **External Rotation Detection**:
     - Primary: gyro_z > 1.2 rad/s
     - Cross-axis verification: gyro_x must not be strongly negative
     - Peak-then-return pattern within 0.3-3.0s window
   - **Baseline Calibration**: 0.8s automatic calibration, recalibrates after each gesture
   - **Conflict Resolution**: Signal strength comparison when both thresholds crossed
   - Interface: `GestureDetectionListener` with `onGestureDetected(GestureType)`

3. **`SensorDataLogger.kt`** - Sensor monitoring with four modes:
   - `FLEXION_DATA_COLLECTION` - Logs wrist flexion data to CSV
   - `NEGATIVE_SAMPLES_COLLECTION` - Logs negative samples to CSV
   - `GESTURE_TEST` - Real-time testing with gesture type display
   - `GESTURE_CONTROL` - Production mode for voice recording control
   - Monitors accelerometer and gyroscope at 50Hz
   - CSV logging to `gesture_data/flexion/` and `gesture_data/negative_samples/`

4. **`WearableMessageListenerService.kt`** - Background service:
   - Listens for `/launch_app` messages from phone
   - Checks display state (only launches if screen is ON)
   - Does not wake the screen (respects user context)
   - Launches MainActivity with enhanced flags

5. **`WearGroqPrefs.kt`** - Manages Groq API key storage and retrieval

6. **Supporting Components**:
   - `complication/MainComplicationService.kt` - Watch face complication
   - `tile/MainTileService.kt` - Wear OS tile
   - `presentation/theme/Theme.kt` - Wear Compose Material theme

### Gesture Detection Flow

```
SensorDataLogger (50Hz sensor events)
    ↓
GestureDetector (threshold-based state machine)
    ↓
GestureDetectionListener.onGestureDetected(GestureType)
    ↓
MainActivity (toggles recording or scrolls results)
```

**Key States:**
- **IDLE**: Baseline monitoring
- **DETECTING**: Threshold crossed, tracking peak
- **CONFIRMED**: Peak + return within time window
- **COOLDOWN**: Brief period after detection (500ms)

**Calibration System:**
- Initial 0.8s baseline calibration on start
- Periodic recalibration after each gesture (handles baseline drift)
- Gravity compensation for accelerometer Z-axis

### Voice Recording & Transcription Flow

```
Wrist Flexion Gesture #1
    ↓
Start MediaRecorder → Record to file
    ↓
Wrist Flexion Gesture #2
    ↓
Stop Recording → Upload to Groq Whisper API
    ↓
Display Transcription → Send to Groq Chat Completions
    ↓
Display LLM Response (with gesture scrolling support)
```

### Screen State Management

The wear app implements multiple layers to keep the screen on:
- Disables ambient mode during recording and result display
- Uses `WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON`
- Acquires `PowerManager.SCREEN_BRIGHT_WAKE_LOCK`
- 15-second timeout after displaying results
- Timeout extends automatically when user performs scroll gestures

## Key Technologies & Dependencies

### Phone App
- Jetpack Compose with Material 3
- Kotlin Coroutines
- Wearable Data Layer API (`play-services-wearable:18.0.0`)
- WorkManager (`work-runtime-ktx:2.9.0`)

### Wear App
- Wear Compose Material (`compose-material:1.2.1`)
- OkHttp (`okhttp:4.12.0`) for Groq API networking
- Kotlin Coroutines with Play Services integration
- Android Sensor Framework (accelerometer, gyroscope at 50Hz)
- MediaRecorder for audio capture
- Horologist Compose Tools (`horologist-compose-tools:0.6.17`)

### Build Configuration
- Gradle: 8.13
- Android Gradle Plugin: 8.13.2
- Kotlin: 2.0.21
- Compose BOM: 2024.04.01

## Working with Gestures

### Modifying Gesture Detection

**Threshold Constants** are in `GestureDetector.kt`:
- Flexion: `FLEXION_GYRO_X_THRESHOLD`, `FLEXION_ACCEL_Z_THRESHOLD`
- Rotation: `ROTATION_GYRO_Z_THRESHOLD`
- Timing: `FLEXION_MIN_DURATION_MS`, `FLEXION_MAX_DURATION_MS`
- Calibration: `BASELINE_DURATION_MS`

**State Machine Logic** in `processSensorEvent()`:
- IDLE: Monitors for threshold crossing
- DETECTING: Tracks peak values
- CONFIRMED: Validates return to baseline
- COOLDOWN: Prevents false positives

**To add a new gesture type:**
1. Add new enum to `GestureType`
2. Add detection thresholds in companion object
3. Implement detection logic in `processSensorEvent()`
4. Update `GestureDetectionListener` handlers in MainActivity

### Testing Gestures

**Change sensor mode** in `MainActivity.kt`:
```kotlin
// Line ~108 in wear/src/main/java/com/g150446/harnessvoice/presentation/MainActivity.kt
private val sensorMode = SensorMode.GESTURE_CONTROL // or GESTURE_TEST
```

**Modes:**
- `GESTURE_TEST`: Shows "Wrist Flexion" or "External Rotation" on screen
- `GESTURE_CONTROL`: Production mode (toggles recording)
- `FLEXION_DATA_COLLECTION`: Logs to CSV for analysis
- `NEGATIVE_SAMPLES_COLLECTION`: Logs negative samples

**Data Collection**: CSV files are written to watch storage at:
- `gesture_data/flexion/*.csv`
- `gesture_data/negative_samples/*.csv`

## Groq API Integration

**Configuration**: API key is set in phone app (`GroqSettingsActivity`) and synced to watch via Wearable Data Layer.

**Two API Calls**:
1. **Whisper API** (`/v1/audio/transcriptions`) - Converts audio to text
   - Model: `whisper-large-v3-turbo`
2. **Chat Completions API** (`/v1/chat/completions`) - Generates response
   - Model: `openai/gpt-oss-120b`

**Implementation**: Both calls in `MainActivity.kt` using OkHttp with multipart/form-data for audio upload.

## Phone-Watch Communication

**Message Path**: `/launch_app`
**Direction**: Phone → Watch
**Protocol**: Wearable Data Layer API

**Launch Behavior**:
- Watch service checks display state via `DisplayManager`
- Only launches if screen is ON (interactive or ambient)
- Does NOT wake the screen from OFF
- Launches with flags: `NEW_TASK | CLEAR_TOP | SINGLE_TOP | BROUGHT_TO_FRONT`

**To modify communication**:
1. Phone sends: `messageClient.sendMessage(nodeId, "/launch_app", byteArray)`
2. Watch receives: `WearableMessageListenerService.onMessageReceived()`
3. Add new paths in both locations for new message types

## Code Organization Notes

**Package Migration**: Code is transitioning from `com.g150446.ringxwatch` to `com.g150446.harnessvoice`. Some legacy ring-related files remain for reference.

**Preserved Components**: Files like `RingAccessibilityService.kt`, `RingDeviceAdminReceiver.kt`, `RingWorker.kt` are from the original RingXwatch project and are preserved for reusable phone-watch communication patterns.

**Different Java Versions**: Phone app uses Java 8, wear app uses Java 11. Be mindful when writing cross-module utilities.

## Common Development Patterns

### Adding a New Gesture Action

1. Update `GestureDetectionListener` in MainActivity:
```kotlin
override fun onGestureDetected(type: GestureType) {
    when (type) {
        GestureType.FLEXION -> handleFlexion()
        GestureType.ROTATION -> handleRotation()
        // Add new gesture type here
    }
}
```

2. The action context depends on current state:
   - During recording: Toggle recording
   - During result display: Scroll content
   - Other states: Define custom behavior

### Modifying Wake Lock Behavior

Wake lock management in `MainActivity.kt` uses multiple approaches:
- Window flags in `enableScreenStayOn()`
- PowerManager wake lock in `acquireWakeLock()`
- Brightness control
- Ambient mode prevention

**Timeout management**: Uses coroutine jobs (`keepScreenOnJob`) that can be cancelled and rescheduled. See `scheduleScreenOff()` for the timeout extension pattern.

### Working with Sensor Data

**Registration** in `SensorDataLogger.kt`:
```kotlin
sensorManager.registerListener(
    this,
    sensor,
    SensorManager.SENSOR_DELAY_FASTEST // ~50Hz
)
```

**Data Flow**:
1. `SensorDataLogger.onSensorChanged()` receives events
2. In GESTURE_CONTROL mode, forwards to `GestureDetector.processSensorEvent()`
3. In data collection modes, logs to CSV

**Accessing Raw Data**: Check logcat with tag "GestureDetector" for detailed sensor values.
