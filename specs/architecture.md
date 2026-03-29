# Architecture Specification

## Overview

Harness Voice is a dual-module Android application consisting of a phone app and a Wear OS app. The system enables hands-free voice recording and transcription through wrist gesture detection, with seamless communication between phone and watch.

## System Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     Harness Voice System                     │
├────────────────────────────┬────────────────────────────────┤
│        Phone App           │         Wear OS App            │
│    (Remote Control)        │    (Main Functionality)        │
│                            │                                │
│  ┌──────────────────┐     │     ┌──────────────────┐      │
│  │   MainActivity   │     │     │   MainActivity   │      │
│  │  (Compose UI)    │     │     │  (Compose UI)    │      │
│  └────────┬─────────┘     │     └────────┬─────────┘      │
│           │               │              │                 │
│  ┌────────▼─────────┐     │     ┌────────▼─────────┐      │
│  │ GroqSettingsActivity│   │     │ GestureDetector  │      │
│  │   (API Config)   │     │     │ (Threshold-based)│      │
│  └────────┬─────────┘     │     └────────┬─────────┘      │
│           │               │              │                 │
│  ┌────────▼─────────┐     │     ┌────────▼─────────┐      │
│  │ DebugMessageMgr  │     │     │ SensorDataLogger │      │
│  │  (Flow-based)    │     │     │  (4 Modes)       │      │
│  └──────────────────┘     │     └────────┬─────────┘      │
│                            │              │                 │
│  ┌──────────────────┐     │     ┌────────▼─────────┐      │
│  │ MessageClient    │◄───┼────►│ WearableMessage  │      │
│  │ NodeClient       │     │     │ ListenerService  │      │
│  │ DataClient       │◄───┼────►│ (Background)     │      │
│  └──────────────────┘     │     └──────────────────┘      │
│                            │                                │
│                            │     ┌──────────────────┐      │
│                            │     │  WearGroqPrefs   │      │
│                            │     │  (API Storage)   │      │
│                            │     └────────┬─────────┘      │
│                            │              │                 │
│                            │     ┌────────▼─────────┐      │
│                            │     │   MediaRecorder  │      │
│                            │     │  (Voice Capture) │      │
│                            │     └────────┬─────────┘      │
│                            │              │                 │
│                            │     ┌────────▼─────────┐      │
│                            │     │    OkHttp        │      │
│                            │     │  (Groq API)      │      │
│                            │     └──────────────────┘      │
└────────────────────────────┴────────────────────────────────┘
                             │
                    ┌────────▼─────────┐
                    │   Groq Cloud     │
                    │  - Whisper API   │
                    │  - Chat API      │
                    └──────────────────┘
```

## Module Architecture

### Phone App Module (`app/`)

**Package**: `com.g150446.harnessvoice`

**Min SDK**: 24 (Android 7.0)

**Target SDK**: 36

**Java Version**: 1.8

#### Component Hierarchy

```
MainActivity (ComponentActivity)
├── LaunchWearAppScreen (Composable)
│   ├── Launch Button
│   ├── Groq Settings Button
│   └── Debug Log Display
│       └── DebugMessageManager (Flow-based)
├── MessageClient (Wearable Data Layer)
├── NodeClient (Wearable Data Layer)
└── GroqSettingsActivity (ComponentActivity)
    ├── API Key Input
    ├── Save & Sync Button
    └── DataClient (Wearable Data Layer)

BootReceiver (BroadcastReceiver)
└── BOOT_COMPLETED handler (inactive)

Legacy Components (preserved from RingXwatch):
├── RingAccessibilityService
├── RingDeviceAdminReceiver
└── RingWorker
```

#### Key Responsibilities

1. **User Interface**: Provide control buttons for watch app
2. **Configuration**: Manage Groq API key settings
3. **Synchronization**: Sync settings to watch via Data Layer
4. **Debugging**: Display real-time debug messages
5. **Communication**: Send launch commands to watch

### Wear OS App Module (`wear/`)

**Package**: `com.g150446.harnessvoice`

**Min SDK**: 30 (Wear OS 2.0)

**Target SDK**: 36

**Java Version**: 11

#### Component Hierarchy

```
MainActivity (ComponentActivity)
├── UI States
│   ├── Initialization (Permission Request)
│   ├── Idle (Waiting for Gesture)
│   ├── Recording (Pulsing Mic Icon)
│   ├── Transcribing (Loading)
│   └── Result Display (Scrollable Text)
├── GestureDetector
│   ├── Baseline Calibration
│   ├── State Machine (IDLE→DETECTING→CONFIRMED→COOLDOWN)
│   ├── Wrist Flexion Detection
│   └── External Rotation Detection
├── SensorDataLogger
│   ├── GESTURE_CONTROL Mode
│   ├── GESTURE_TEST Mode
│   ├── FLEXION_DATA_COLLECTION Mode
│   └── NEGATIVE_SAMPLES_COLLECTION Mode
├── MediaRecorder
│   ├── Audio Source Configuration
│   ├── Recording to M4A
│   └── File Management
├── Groq API Integration
│   ├── Whisper Transcription
│   └── Chat Completions
├── Screen Management
│   ├── Wake Lock Acquisition
│   ├── Ambient Mode Prevention
│   └── Timeout Management
└── WearGroqPrefs
    └── API Key Storage

WearableMessageListenerService (Service)
├── Message Reception (/launch_app)
├── Display State Check
└── Activity Launch

Supporting Components:
├── MainComplicationService
├── MainTileService
└── Theme (Wear Compose Material)
```

#### Key Responsibilities

1. **Gesture Detection**: Real-time wrist gesture recognition
2. **Voice Recording**: Capture audio via MediaRecorder
3. **Transcription**: Upload audio to Groq Whisper API
4. **LLM Integration**: Generate responses via Groq Chat API
5. **Display Management**: Keep screen on during recording/results
6. **Communication**: Receive commands from phone
7. **Data Collection**: Log sensor data for analysis (optional)

## Data Flow

### Primary Use Case: Voice Recording and Transcription

```
1. User performs wrist flexion gesture
   ↓
2. SensorDataLogger receives sensor events (50Hz)
   ↓
3. GestureDetector processes events through state machine
   ↓
4. CONFIRMED state reached → callback to MainActivity
   ↓
5. MainActivity starts MediaRecorder
   ↓
6. UI updates to "Recording..." with pulsing mic icon
   ↓
7. Screen stays on (wake lock + ambient mode disabled)
   ↓
8. User performs second wrist flexion gesture
   ↓
9. GestureDetector confirms gesture → callback
   ↓
10. MainActivity stops MediaRecorder
    ↓
11. Audio file saved to cache directory (M4A format)
    ↓
12. UI updates to "Transcribing..."
    ↓
13. Upload file to Groq Whisper API (multipart/form-data)
    ↓
14. Receive transcription JSON response
    ↓
15. Display transcription on screen
    ↓
16. UI updates to "Getting response..."
    ↓
17. Send transcription to Groq Chat Completions API (JSON)
    ↓
18. Receive LLM response JSON
    ↓
19. Display LLM response below transcription
    ↓
20. Enable gesture scrolling (wrist flexion scrolls text)
    ↓
21. Screen stays on for 15 seconds (extends with gestures)
    ↓
22. Timeout → return to idle state
```

### Configuration Flow: API Key Sync

```
1. User opens Groq Settings in phone app
   ↓
2. Enters API key in text field
   ↓
3. Presses "Save & Sync to Watch"
   ↓
4. Phone saves key to SharedPreferences
   ↓
5. Phone creates DataMapRequest with key
   ↓
6. DataClient.putDataItem() with urgent flag
   ↓
7. Wearable Data Layer API syncs to watch
   ↓
8. Watch receives data change event
   ↓
9. Watch extracts API key from DataMap
   ↓
10. Watch saves to WearGroqPrefs (SharedPreferences)
    ↓
11. API key available for Groq API calls
```

### Launch Flow: Phone to Watch

```
1. User opens phone app
   ↓
2. Presses "Launch Watch App" button
   ↓
3. Phone queries connected nodes via NodeClient
   ↓
4. Phone sends message to /launch_app path via MessageClient
   ↓
5. Watch WearableMessageListenerService receives message
   ↓
6. Service checks display state via DisplayManager
   ↓
7. If screen is ON (interactive or ambient):
   ├─ Create Intent for MainActivity
   ├─ Add flags (NEW_TASK, CLEAR_TOP, etc.)
   └─ startActivity()
   ↓
8. If screen is OFF:
   └─ Ignore launch request (log and return)
   ↓
9. Watch app launches and requests permissions
```

## State Management

### Phone App States

**MainActivity States**:
- **Idle**: Normal state, buttons enabled
- **Launching**: Sending launch command, loading indicator
- **Error**: Connection or send error, error message displayed

**GroqSettingsActivity States**:
- **Idle**: Editing API key
- **Saving**: Syncing to watch, loading indicator
- **Success**: "Saved and synced" message
- **Error**: Network or sync error message

### Wear App States

**MainActivity States**:
```kotlin
sealed class AppState {
    object Initializing          // Permission request
    object Idle                  // Waiting for gesture
    object Recording             // Recording audio
    object Transcribing          // Uploading to Whisper
    object GettingResponse       // Calling Chat Completions
    object DisplayingResults     // Showing transcription + LLM response
    object ShowingExitConfirm    // External rotation confirmation
}
```

**GestureDetector States**:
```kotlin
enum class DetectionState {
    IDLE,           // Baseline monitoring
    DETECTING,      // Threshold exceeded, tracking peak
    CONFIRMED,      // Peak + return to baseline
    COOLDOWN        // Brief period after detection
}
```

**SensorDataLogger Modes**:
```kotlin
enum class SensorMode {
    GESTURE_CONTROL,               // Production mode
    GESTURE_TEST,                  // Testing mode
    FLEXION_DATA_COLLECTION,       // Collect flexion samples
    NEGATIVE_SAMPLES_COLLECTION    // Collect negative samples
}
```

## Threading Model

### Phone App

**Main Thread**:
- UI rendering (Compose)
- User interactions (button clicks)
- Debug message display

**Coroutine Scopes**:
```kotlin
rememberCoroutineScope() {
    launch {
        // Message sending
        messageClient.sendMessage(...)
    }
}
```

**Dispatchers**:
- `Dispatchers.Main`: UI updates
- `Dispatchers.IO`: Network operations (Data Layer API)

### Wear App

**Main Thread**:
- UI rendering (Wear Compose)
- Gesture callbacks
- State updates

**Background Threads**:
- **Sensor Processing**: SensorEventListener callbacks (sensor thread)
- **Network**: OkHttp operations (IO thread pool)
- **MediaRecorder**: Audio recording (native thread)

**Coroutine Scopes**:
```kotlin
CoroutineScope(Dispatchers.IO).launch {
    // API calls (Whisper, Chat Completions)
    val response = client.newCall(request).execute()

    withContext(Dispatchers.Main) {
        // UI updates
        transcriptionText = result
    }
}

CoroutineScope(Dispatchers.Main) {
    // Wake lock management
    delay(15000)
    releaseWakeLock()
}
```

## Dependency Management

### Version Catalog (`gradle/libs.versions.toml`)

**Build Tools**:
- Gradle: 8.13
- Android Gradle Plugin: 8.13.2
- Kotlin: 2.0.21

**AndroidX Libraries**:
- Core KTX: 1.17.0
- Lifecycle Runtime: 2.9.4
- Activity Compose: 1.11.0
- Compose BOM: 2024.04.01

**Wear Specific**:
- Wear Compose Material: 1.2.1
- Wear Compose Foundation: 1.2.1
- Horologist: 0.6.17

**Communication**:
- Play Services Wearable: 18.0.0
- Coroutines Play Services: 1.9.0

**Networking**:
- OkHttp: 4.12.0

### Shared Dependencies

Both modules:
- Jetpack Compose (different Material variants)
- Kotlin Coroutines
- Play Services Wearable

Phone only:
- Material 3
- WorkManager

Wear only:
- OkHttp (for Groq API)
- Media (for audio recording)
- Wear SDK libraries

## Security Architecture

### API Key Management

**Storage**:
```
Phone: /data/data/com.g150446.harnessvoice/shared_prefs/groq_prefs.xml
Watch: /data/data/com.g150446.harnessvoice/shared_prefs/groq_prefs.xml
```

**Protection**:
- Private app storage (other apps cannot access)
- Encrypted Android Auto Backup
- Not logged or displayed in plain text

**Transmission**:
- Bluetooth encrypted connection (Wearable Data Layer)
- No network transmission (local sync only)

### Audio Data

**Storage**:
- Temporary files in cache directory
- Deleted after API upload
- Not accessible by other apps

**Transmission**:
- HTTPS to Groq API (TLS 1.2+)
- No local network transmission
- No long-term storage

### Permissions

**Phone App**:
- `POST_NOTIFICATIONS`: For notification display (Android 13+)
- `RECEIVE_BOOT_COMPLETED`: Auto-start capabilities
- No dangerous permissions required

**Wear App**:
- `RECORD_AUDIO`: Voice recording (dangerous permission)
- `INTERNET`: API calls to Groq
- `WAKE_LOCK`: Screen management
- `RECEIVE_BOOT_COMPLETED`: Auto-start service

## Performance Characteristics

### Latency

**Gesture Detection**: < 20ms (one sensor cycle)

**Voice Recording Start**: < 100ms

**API Transcription**: 2-10 seconds (depends on audio length)

**LLM Response**: 1-6 seconds (depends on prompt)

**Total End-to-End**: 5-30 seconds (recording to result display)

### Resource Usage

**Phone App**:
- Memory: ~50 MB
- CPU: < 5% (idle)
- Battery: Negligible

**Wear App**:
- Memory: ~100 MB (with gesture detection active)
- CPU: 10-20% (gesture detection), 30-50% (recording)
- Battery: 1-2% per hour (gesture detection), 5-10% per hour (active use)
- Sensor: 50Hz accelerometer + gyroscope

### Network Usage

**Data Layer Sync**: < 1 KB per sync

**Voice Upload**: ~1 MB per minute of audio

**API Responses**: < 5 KB typically

## Build and Deployment

### Build Variants

**Debug**:
- No minification
- Debug signing
- Verbose logging

**Release**:
- ProGuard disabled (for now)
- Release signing
- Minimal logging

### Multi-Module Build

```bash
# Build all modules
./gradlew assembleDebug

# Build specific module
./gradlew :app:assembleDebug
./gradlew :wear:assembleDebug

# Install to devices
./gradlew :app:installDebug    # Phone
./gradlew :wear:installDebug   # Watch
```

### Deployment Requirements

**Phone**:
- Android 7.0+ (API 24)
- Google Play Services
- Bluetooth capability

**Watch**:
- Wear OS 2.0+ (API 30)
- Accelerometer and gyroscope sensors
- Microphone
- Google Play Services

## Future Architecture Considerations

### Planned Enhancements

1. **Modular Architecture**: Extract gesture detection into library module
2. **Repository Pattern**: Centralize data access
3. **ViewModel**: Proper MVVM architecture for state management
4. **Dependency Injection**: Hilt or Koin for DI
5. **Room Database**: Persistent storage for settings and history
6. **WorkManager**: Background sync and processing

### Scalability

**Current Limitations**:
- Single user per device
- No multi-device sync (beyond phone-watch)
- No conversation history persistence
- No offline capability

**Future Improvements**:
- Cloud sync for settings and history
- Multi-watch support
- Offline gesture detection (already works)
- Cached API responses
- Background audio processing
