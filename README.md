# Harness Voice

An Android application for Wear OS that enables wrist gesture detection to control voice recording. The app uses sensor-based gesture recognition to toggle recording, providing hands-free control of voice transcription features.

## Migration from RingXwatch

This project originated as **RingXwatch**, which was designed to control an AI voice assistant using ring tap gestures. The app has been migrated and rebranded to **Harness Voice** with a new focus on wrist gesture detection.

**Key Changes:**
- **Project Name**: RingXwatch → Harness Voice
- **Package Name**: `com.g150446.ringxwatch` → `com.g150446.harnessvoice`
- **Primary Function**: Ring gesture control → Wrist gesture detection
- **Target Gestures**: Ring tap gestures → Wrist flexion and external rotation

**Note on Code Preservation**: Some ring gesture related code remains in the codebase for reference and reusable components (e.g., phone-watch communication infrastructure, Wearable Data Layer API usage). These components may be useful for future features or reference implementations.

## Features

### ⌚ Wear OS Communication
- Phone-to-watch communication using Wearable Data Layer API
- Remote watch app launch from phone
- **Screen-state aware launch**: Works when screen is ON (interactive or ambient)
- **No wake locks on Wear**: Does not wake the screen from OFF
- **Respects user context**: Ignores taps when watch screen is OFF
- Manual launch button in phone app

### 🎙️ Voice Transcription (Groq Whisper)
- Wrist gesture detection toggles voice recording while the watch app is open
- Second gesture stops recording and uploads audio to Groq Whisper Large v3 Turbo
- Displays transcribed text on the watch
- After transcription, the text is sent to Groq Chat Completions using `openai/gpt-oss-120b` and the generated response is shown below the transcription
- API key is configured on the phone app and synced to the watch automatically
- **Screen stays on**: Ambient mode is disabled and screen remains fully interactive during recording
- **Extended visibility**: Screen remains on for 15 seconds after displaying transcription and LLM response results
- **Gesture scrolling**: When LLM response text is too long to fit on screen, use wrist flexion gestures to scroll down and read more content. Each gesture scrolls by two-thirds screen height instantly. The display timeout automatically extends while actively scrolling (resets to 15 seconds with each gesture)

### 🤲 Wrist Gesture Detection
- **Real-time sensor monitoring**: Accelerometer and gyroscope data analyzed at 50Hz
- **Threshold-based detection**: Simple state machine algorithm (no ML required) with 152-539x signal-to-noise ratio
- **Current Gesture Support**:
  - ✅ **Wrist Flexion**: Fully implemented and working
    - Primary detector: gyro_x rotation threshold (-4.0 rad/s)
    - Secondary confirmation: accel_z deviation (8.0 m/s²)
    - Peak-then-return pattern detection within 0.3-3.0 second window
    - Automatic baseline calibration (0.8 seconds)
  - ✅ **External Rotation**: Fully implemented and working
    - Primary detector: gyro_z rotation threshold (1.2 rad/s)
    - Cross-axis verification: gyro_x must not be strongly negative (to distinguish from flexion)
    - Peak-then-return pattern detection within 0.3-3.0 second window
    - Automatic baseline calibration (0.8 seconds)
- **Dual Gesture Detection**: System can detect and distinguish both gestures simultaneously
  - Simultaneous threshold checking for both gesture types
  - Conflict resolution: Signal strength comparison when both thresholds crossed
  - Real-time classification with cross-axis verification
- **Four Mode System**:
  - **Flexion Data Collection Mode**: Logs wrist flexion gesture data to CSV for analysis
  - **Negative Samples Collection Mode**: Logs negative samples (non-gesture movements) to CSV for analysis
  - **Gesture Test Mode**: Real-time gesture testing with automatic gesture distinction
    - Shows "Waiting Gesture" initially
    - After 0.5 seconds of sensor analysis, displays determined gesture type:
      - "Wrist Flexion" for simple wrist flexion gestures
      - "External Rotation" for external wrist rotation gestures
    - Uses cumulative gyro X threshold (50 rad/s) to distinguish gesture types
    - Comprehensive sensor data logged to logcat for analysis
  - **Gesture Control Mode**: Real-time gesture control - toggles voice recording with wrist flexion gesture
- **Gesture Distinction Algorithm**:
  - **Primary Discriminator**: Cumulative Gyro X (0.5 seconds after detection)
    - Wrist Flexion: < 50 rad/s (typically 2-3 rad/s)
    - External Rotation: > 50 rad/s (typically 108-130 rad/s)
    - Perfect separation with 33x safety margin
  - **Analysis Period**: 0.5 seconds of sensor data collection
  - **Display Delay**: Message shown after analysis completes (0.5s delay)
  - **Comprehensive Logging**: Tracks cumulative and peak values for gyro (X, Y, Z) and accelerometer (X, Y, Z)
- **Integration**: 
  - **Test Mode**: Shows detection status with gesture type distinction instead of recording interface
  - **Control Mode**: Gestures toggle voice recording
  - **Result Display Mode**: Wrist flexion gestures scroll through long LLM response text (two-thirds screen height per gesture)
- **Periodic Baseline Recalibration**: 
  - Automatically recalibrates baseline after each gesture detection to handle baseline drift
  - Ensures reliable gesture detection even after multiple gestures or wrist position changes
  - Recalibration happens during cooldown period (800ms) to minimize impact on detection

### 🐛 Debug Logging
- Real-time debug messages displayed on screen
- Timestamped event tracking
- Message transmission confirmation

## Architecture

### Phone App (`app/`)

**Components:**
- `MainActivity.kt` - Main UI with watch app launch controls and debug log
- `BootReceiver.kt` - Boot receiver (no longer starts services)
- `DebugMessageManager.kt` - Debug message flow manager
- `GroqSettingsActivity.kt` - Groq API key configuration

**Key Technologies:**
- Jetpack Compose for UI
- Kotlin Coroutines for async operations
- Google Play Services Wearable API

### Wear OS App (`wear/`)

**Components:**
- `MainActivity.kt` - Watch app entry point with:
  - Voice recording via MediaRecorder
  - Groq API integration for transcription and LLM responses
  - Ambient mode prevention during recording and result display
  - Extended screen-on time management (15 seconds after results, auto-extends with gestures)
  - **Wrist gesture detection integration** (gesture detector triggers recording toggle)
  - **Result display gesture scrolling** (wrist flexion scrolls through long text, timeout extends with each gesture)
- `GestureDetector.kt` - **NEW**: Threshold-based dual gesture recognition:
  - Baseline calibration system
  - Wrist flexion detection (gyro_x + accel_z thresholds)
  - External rotation detection (gyro_z threshold with cross-axis verification)
  - Simultaneous detection and distinction of both gesture types
  - Conflict resolution via signal strength comparison
  - State machine for peak-then-return pattern recognition
  - Real-time sensor event processing
- `SensorDataLogger.kt` - Sensor monitoring and data collection:
  - Four mode support (flexion data collection / negative samples collection / gesture test / gesture control)
  - CSV logging for data analysis (in data collection modes)
  - Sensor event forwarding to gesture detector (in test and control modes)
  - Automatic folder organization (gesture_data/flexion/ and gesture_data/negative_samples/)
- `WearableMessageListenerService.kt` - Background service that:
  - Listens for launch commands from phone
  - Checks display state via DisplayManager (interactive or ambient)
  - Launches watch app UI without waking the screen

## How It Works

### Phone-to-Watch Communication

1. Phone app sends message to `/launch_app` path
2. Watch's `WearableMessageListenerService` receives message
3. **Screen state check**: Proceeds only if display is ON (interactive or ambient)
4. **No wake locks on Wear**: Does not attempt to wake the screen
5. Launches watch MainActivity with enhanced flags (NEW_TASK + CLEAR_TOP + SINGLE_TOP + BROUGHT_TO_FRONT)
6. When the app is running: wrist gestures control voice recording; on stop, audio is sent to Groq for transcription and the result is shown
7. Respects user context: If screen is OFF, the launch is ignored

## Setup

### Requirements

- Android phone (API 24+)
- Wear OS watch paired with phone

### Permissions

**Phone App:**
- `POST_NOTIFICATIONS` - Show notifications (Android 13+)
- `RECEIVE_BOOT_COMPLETED` - Boot receiver registration

**Wear App:**
- `WAKE_LOCK` - Present for compatibility; Wear app no longer wakes the screen
- `RECEIVE_BOOT_COMPLETED` - Auto-start message listener service
 - `RECORD_AUDIO` - Capture voice on the watch
 - `INTERNET` - Upload audio to Groq for transcription

### Installation

1. Clone the repository:
   ```bash
   git clone https://github.com/g150446/harness-voice.git
   ```

2. Open in Android Studio

3. Build and install both modules:
   ```bash
   ./gradlew :app:installDebug
   ./gradlew :wear:installDebug
   ```

4. Grant permissions when prompted:
   - Notification permission (if required)

5. **Ready to use**:
   - Configure transcription: Open "🔑 Groq API Settings" in the phone app, paste `GROQ_API_KEY`, then Save & Sync to watch

## Usage

### Phone App

1. **Launch App** - Opens main interface
2. **Manual Launch** - Use "Launch Watch App" button to launch watch app
3. **Groq Settings** - Configure Groq API key for transcription
4. **Debug Log** - View real-time events at bottom of screen

### Watch App

- Can be launched manually from phone app button
- When open: wrist flexion gesture starts voice recording; gesture again to stop and transcribe via Groq; text appears on screen
- External rotation gesture shows close confirmation (can be cancelled with wrist flexion)
- **Scrolling long results**: If the LLM response is too long to fit on screen, perform wrist flexion gestures to scroll down. Each gesture scrolls by two-thirds of the screen height instantly. The display timeout extends automatically while you're actively scrolling

## Technical Details

### Build Configuration

- **Gradle**: 8.13
- **Android Gradle Plugin**: 8.13.0
- **Kotlin**: 2.0.21
- **Compile SDK**: 36
- **Target SDK**: 36
- **Min SDK**: 24 (phone), 30 (wear)

### Key Dependencies

```kotlin
// Wear connectivity
implementation("com.google.android.gms:play-services-wearable:18.0.0")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

// Wear networking (Groq transcription)
implementation("com.squareup.okhttp3:okhttp:4.12.0")

// Compose UI
implementation(platform("androidx.compose:compose-bom:2024.04.01"))
```

## Known Issues & Limitations

- Lower battery impact on Wear: no wake locks are used to wake the screen

## Development History

### v1.0 - Initial Implementation
- Phone app with button to launch watch app
- Basic Wearable Data Layer communication
- Wake lock support for reliable watch launching

### v2.0 - Screen-State Aware Wear Launch
- **Display-aware behavior**: Only launches when the watch display is ON
- **Ambient support**: Works in both interactive and ambient states
- **No wake locks on Wear**: Does not wake the screen from OFF
- **User-friendly**: Respects user context and reduces battery impact

### v3.3 - Screen Stay-On During Recording & Results
- **Ambient mode disabled**: Explicitly prevents ambient mode entry during recording
- **Extended screen-on time**: Screen stays on during recording and for 15 seconds after displaying Groq API results
- **Multiple methods**: Window flags, wake locks, brightness control, and activity APIs combined for maximum reliability
- **App-isolated**: Only affects this app's activity, does not impact other apps or system settings
- **Recording protection**: Screen remains fully interactive during voice recording
- **Result display protection**: Keeps screen on for 15 seconds after transcription and LLM response are shown

### v3.0 - Wrist Gesture Detection
- **Project Migration**: Rebranded from RingXwatch to Harness Voice
- **Threshold-Based Gesture Detection**: Implemented wrist flexion detection using threshold algorithm
- **Gesture Detection Algorithm**: 
  - Wrist flexion: gyro_x < -1.2 rad/s with gyro_z near baseline (relaxed thresholds for better detection rate)
  - Secondary confirmation: accel_z > 3.5 m/s² deviation from baseline (gravity-compensated)
  - Peak-then-return pattern detection within 0.12-3.0 second window
  - Automatic baseline calibration (0.8 seconds)
  - Gravity compensation: Uses tilt angle to subtract gravity effect from accelerometer Z-axis
- **Sensor Integration**: Real-time accelerometer and gyroscope monitoring at 50Hz
- **Baseline Calibration**: 0.8-second automatic calibration for stable gesture recognition
- **Four Mode System**: 
  - Flexion data collection mode
  - Negative samples collection mode
  - Gesture test mode (shows "Waiting Gesture" / "Wrist Flexion" / "External Rotation" on screen)
  - Gesture control mode (toggles recording with wrist flexion)
- **Current Status**: 
  - ✅ Wrist flexion detection: **Complete and working** - Relaxed thresholds for better recognition rate
  - ✅ Gesture test mode: **Complete** - Shows detected gesture type with automatic distinction
  - ✅ Gesture control mode: **Complete** - Toggles recording on wrist flexion gesture
- **Gesture Analysis**: Analyzed sensor data showing strong signal-to-noise ratio, confirming simple threshold-based approach is sufficient (no ML required)
- **Data Organization**: Collected gesture data automatically saved to gesture-specific folders (gesture_data/flexion/ and gesture_data/negative_samples/)
- **Code Preservation**: Ring gesture related code preserved for reusable components and reference

### v3.1 - Gesture Distinction Feature
- **Automatic Gesture Classification**: Gesture test mode now distinguishes between wrist flexion and external rotation
- **Distinction Algorithm**: 
  - Uses cumulative gyro X (0.5 seconds after detection) as primary discriminator
  - Threshold: 50 rad/s (flexion: 2-3 rad/s, rotation: 108-130 rad/s)
  - Perfect separation with 33x safety margin
- **Enhanced Gesture Analysis**:
  - Comprehensive sensor tracking: cumulative and peak values for gyro (X, Y, Z) and accelerometer (X, Y, Z)
  - 0.5-second analysis period for accurate gesture classification
  - Detailed logging to logcat for debugging and analysis
- **User Experience**:
  - Delayed message display: Shows "Waiting Gesture" for 0.5 seconds during analysis
  - Clear feedback: Displays "Wrist Flexion" or "External Rotation" based on sensor data
  - 1.5-second total display time (0.5s analysis + 1.0s message display)
- **Implementation**: Option 1 (Primary-Only) distinction logic for simplicity and reliability

### v3.4 - Result Display Gesture Scrolling
- **Gesture-Based Scrolling**: Wrist flexion gestures can now scroll through long LLM response text in the result display scene
- **Scroll Behavior**:
  - Each wrist flexion gesture scrolls down by two-thirds screen height instantly
  - Scrolls forward only (no backward scrolling)
  - Works seamlessly with existing gesture detection system
- **Periodic Baseline Recalibration**:
  - Automatically recalibrates baseline after each gesture detection to handle baseline drift
  - Prevents gesture detection from stopping after multiple gestures
  - Recalibration happens during cooldown period (800ms) to minimize impact
- **Smart Timeout Extension**:
  - Result display timeout (15 seconds) automatically extends when user performs gestures
  - Each gesture resets the timeout, allowing users to scroll through very long text
  - Prevents premature cleanup of gesture detection while actively scrolling
- **Technical Implementation**:
  - Gesture detection initialized specifically for result display mode
  - Scroll state managed via Compose `ScrollState` with programmatic control
  - Timeout job tracking allows cancellation and rescheduling
  - Clean integration with existing gesture detection infrastructure

## Current Status

### Completed Features ✅
- Wrist flexion gesture detection - threshold-based, working with relaxed thresholds for better recognition
- Voice recording with gesture control (gesture control mode - wrist flexion toggles recording)
- Gesture test mode with automatic gesture distinction - distinguishes "Wrist Flexion" vs "External Rotation"
- Gesture distinction algorithm - uses cumulative gyro X threshold (50 rad/s) for reliable classification
- Comprehensive gesture analysis - tracks cumulative and peak values for all sensor axes
- Groq API integration for transcription and LLM responses
- Four-mode sensor system (flexion data collection / negative samples collection / gesture test / gesture control)
- Baseline calibration system
- Gravity compensation for accurate accelerometer readings
- Organized data collection (gesture-specific folders)
- **Result display gesture scrolling** - wrist flexion gestures scroll through long LLM response text (two-thirds screen height per gesture)
- **Periodic baseline recalibration** - automatic recalibration after each gesture to handle baseline drift
- **Smart timeout extension** - result display timeout automatically extends when user is actively scrolling

### Planned Features 🔄
- Gesture sensitivity customization
- Multiple gesture pattern recognition

### Preserved for Reference 📚
- Phone-watch communication infrastructure

## Future Enhancements

- [ ] Gesture pattern customization (sensitivity, timing)
- [ ] Multiple gesture recognition (sequence of gestures)
- [ ] Gesture-based shortcuts (different gestures for different actions)
- [ ] Sensor data analysis tools for gesture tuning

## License

This project is private and not licensed for public use.


