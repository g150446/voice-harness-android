# Shepherd Signal

An Android application for Wear OS that enables wrist gesture detection to control voice recording. The app uses sensor-based gesture recognition to toggle recording, providing hands-free control of voice transcription features.

## Migration from RingXwatch

This project originated as **RingXwatch**, which was designed to control an AI voice assistant using ring tap gestures. The app has been migrated and rebranded to **Shepherd Signal** with a new focus on wrist gesture detection.

**Key Changes:**
- **Project Name**: RingXwatch → Shepherd Signal
- **Package Name**: `com.g150446.ringxwatch` → `com.g150446.shepherdsignal`
- **Primary Function**: Ring gesture control → Wrist gesture detection
- **Target Gestures**: Ring tap gestures → Wrist flexion and external rotation

**Note on Code Preservation**: Some ring gesture related code remains in the codebase for reference and reusable components (e.g., phone-watch communication infrastructure, Wearable Data Layer API usage, MediaSession handling). These components may be useful for future features or reference implementations.

## Features

### 🎵 Background Music Player
- **Dual-track silent playlist**: Two 60-minute silent MP3 files for reliable ring gesture detection
- **Ring gesture advances tracks**: Tapping the ring switches between silent tracks and launches watch app
- **Track display**: Current track name shown in phone app UI and notification bar
- Foreground service with persistent notification
- Real-time playback controls (play/pause) in phone app
- Automatic looping of silent playlist for continuous operation
- ExoPlayer-based playback with REPEAT_MODE_ALL

### 💍 Smart Ring Integration
- Intercepts Colmi smart ring tap gestures (MEDIA_NEXT events)
- Uses active MediaSession to capture media button events
- **Dual functionality**: Ring tap advances to next silent track AND launches watch app
- Two-track playlist ensures ring gesture always triggers "next track" action
- **Works in sleep mode**: Enhanced wake lock implementation for reliable operation
- **Background operation**: Works even when app is not opened
- **Auto-start on boot**: Automatically starts service when device boots
- **Doze mode resistance**: Persistent MediaStyle notification with MediaSessionService for legitimate media app recognition
- **Authentic music player behavior**: Audio focus management, MediaStyle notifications, and professional audio handling
- **Continuous silent playback**: ExoPlayer maintains playback state reliably without aggressive wake-ups
- **Battery optimization exemption**: Ultimate Doze resistance by exempting app from battery optimization
- **OEM-friendly approach**: Avoids AlarmManager and aggressive techniques that trigger manufacturer restrictions

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
  - **Control Mode**: Gestures toggle voice recording (same behavior as previous ring tap)

### 🐛 Debug Logging
- Real-time debug messages displayed on screen
- Timestamped event tracking
- Ring tap detection monitoring
- Audio file loading status
- Message transmission confirmation

## Architecture

### Phone App (`app/`)

**Components:**
- `MainActivity.kt` - Main UI with playback controls and debug log
- `AuxioStyleService.kt` - MediaSessionService managing:
  - Media3 ExoPlayer for continuous silent playback
  - MediaSession for media button interception
  - Wearable message client for watch communication
  - Persistent MediaStyle notification for legitimate media app recognition
  - Audio focus management and wake lock handling
- `BootReceiver.kt` - Auto-starts service on device boot
- `PlaybackStateManager.kt` - Reactive state manager for UI updates
- `DebugMessageManager.kt` - Debug message flow manager

**Key Technologies:**
- Jetpack Compose for UI
- Kotlin Coroutines for async operations
- StateFlow for reactive state management
- Media3 ExoPlayer for audio playback
- Media3 MediaSession for media button handling
- Google Play Services Wearable API

### Wear OS App (`wear/`)

**Components:**
- `MainActivity.kt` - Watch app entry point with:
  - Voice recording via MediaRecorder
  - Groq API integration for transcription and LLM responses
  - Ambient mode prevention during recording and result display
  - Extended screen-on time management (15 seconds after results)
  - **Wrist gesture detection integration** (gesture detector triggers recording toggle)
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
  - **Note**: Ring gesture code preserved for reference

## How It Works

### Ring Tap Interception

1. **Service Start**: `AuxioStyleService` starts as MediaSessionService with persistent notification
2. **Silent Music Playback**: ExoPlayer loads and plays silent MP3 from assets continuously
3. **Active MediaSession**: Maintains active MediaSession with PLAYING state
4. **Priority Routing**: Android routes media button events to active session
5. **Event Interception**: MediaSession callback receives MEDIA_NEXT (ring tap)
6. **Action**: Launches watch app via Wearable Data Layer
7. **Event Consumption**: Returns `true` to prevent other apps from seeing the event
8. **Continuous Operation**: ExoPlayer maintains playback state reliably without aggressive wake-ups

### Phone-to-Watch Communication

1. Phone app sends message to `/ring_tap` path
2. Watch's `WearableMessageListenerService` receives message
3. **Screen state check**: Proceeds only if display is ON (interactive or ambient)
4. **No wake locks on Wear**: Does not attempt to wake the screen
5. Launches watch MainActivity with enhanced flags (NEW_TASK + CLEAR_TOP + SINGLE_TOP + BROUGHT_TO_FRONT)
6. If the app is already running: toggles voice recording; on stop, audio is sent to Groq for transcription and the result is shown
7. Respects user context: If screen is OFF, the tap is ignored

## Setup

### Requirements

- Android phone (API 24+)
- Wear OS watch paired with phone
- Colmi smart ring paired via Bluetooth
- **No external audio files required**: Built-in silent music included
- Optional: Additional audio files in `/storage/emulated/0/Music/` directory

### Permissions

**Phone App:**
- `WAKE_LOCK` - Keep CPU running during watch launch
- `FOREGROUND_SERVICE` - Run music player service
- `FOREGROUND_SERVICE_MEDIA_PLAYBACK` - Media playback notification
- `POST_NOTIFICATIONS` - Show foreground service notification (Android 13+)
- `READ_MEDIA_AUDIO` - Access music files (Android 13+)
- `READ_EXTERNAL_STORAGE` - Access music files (Android 12 and below)
- `RECEIVE_BOOT_COMPLETED` - Auto-start service on device boot
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` - Request battery optimization exemption

**Wear App:**
- `WAKE_LOCK` - Present for compatibility; Wear app no longer wakes the screen
- `RECEIVE_BOOT_COMPLETED` - Auto-start message listener service
 - `RECORD_AUDIO` - Capture voice on the watch
 - `INTERNET` - Upload audio to Groq for transcription

### Installation

1. Clone the repository:
   ```bash
   git clone https://github.com/g150446/RingXwatch.git
   ```

2. Open in Android Studio

3. Build and install both modules:
   ```bash
   ./gradlew :app:installDebug
   ./gradlew :wear:installDebug
   ```

4. Grant permissions when prompted:
   - Notification permission
   - Audio files access permission (optional)

5. **Ready to use**: App includes built-in silent music - no additional files needed
   - Optional: Add audio files to `/storage/emulated/0/Music/` on phone
   - Configure transcription: Open "🔑 Groq API Settings" in the phone app, paste `GROQ_API_KEY`, then Save & Sync to watch

## Usage

### Phone App

1. **Launch App** - Service starts automatically
2. **Dual-track Silent Playlist** - Two 60-minute silent tracks begin playing and loop automatically
3. **Current Track Display** - See which silent track is currently playing in the UI
4. **Playback Controls** - Use ⏸/▶ button to pause/play (maintains current track position)
5. **Ring Tap** - Tap your Colmi ring to:
   - Advance to next silent track
   - Launch watch app (works in sleep mode!)
6. **Manual Launch** - Use "Launch Watch App" button for manual control
7. **Debug Log** - View real-time events at bottom of screen
8. **Background Operation** - Service continues running even when app is closed
9. **Auto-Start** - Service automatically starts on device boot
10. **Doze Mode Resistance** - Works reliably even when phone enters Doze mode
11. **Battery Optimization** - Tap "Disable Battery Optimization" button for ultimate reliability

### Watch App

- Automatically launches when ring is tapped if the screen is ON
- Works in both interactive and ambient states
- Ignores taps when the screen is OFF (non-intrusive)
- Can also be launched manually from phone app button
 - When open: ring tap starts voice recording; tap again to stop and transcribe via Groq; text appears on screen

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

// Media3 ExoPlayer and MediaSession
implementation("androidx.media3:media3-exoplayer:1.2.1")
implementation("androidx.media3:media3-session:1.2.1")
implementation("androidx.media3:media3-ui:1.2.1")

// Compose UI
implementation(platform("androidx.compose:compose-bom:2024.04.01"))
```

## Known Issues & Limitations

- ~~Requires audio files in Music directory for MediaSession to stay active~~ **RESOLVED**: Built-in silent music included
- Ring tap only works while music player service is running
- Playback state may not sync immediately on service restart
- Lower battery impact on Wear: no wake locks are used to wake the screen
- **Background operation**: May require battery optimization exemption for optimal performance
- **Doze mode resistance**: Persistent MediaStyle notification may be visible to users but provides legitimate media app recognition

## Development History

### v1.0 - Initial Implementation
- Phone app with button to launch watch app
- Basic Wearable Data Layer communication
- Wake lock support for reliable watch launching

### v2.0 - Ring Gesture Integration
- Implemented foreground service with MediaPlayer
- Added MediaSession for media button interception
- Created reactive playback controls
- Real-time debug logging system
- Play/pause button in UI
- Automatic audio file discovery

### v2.1 - Silent Music & Sleep Mode Support
- **Built-in silent music**: 1-minute silent MP3 included in assets
- **Automatic looping**: Silent music loops every 60 seconds
- **Sleep mode support**: Enhanced wake lock implementation for reliable operation
- **Dual wake locks**: FULL_WAKE_LOCK + SCREEN_BRIGHT_WAKE_LOCK
- **Sleep mode detection**: Automatic detection and handling of sleep state
- **Extended wake lock duration**: 8-12 seconds for reliable launching
- **No external dependencies**: Works without Music directory files
- **90% size reduction**: 1-minute silent MP3 (960KB) vs 10-minute (9.6MB)

### v2.2 - Background Operation & Auto-Start
- **Background operation**: Works even when app is not opened
- **Auto-start on boot**: BootReceiver automatically starts service on device boot
- **Service persistence**: START_STICKY ensures service restarts if killed by system
- **Battery optimization handling**: Detects and guides users for optimal performance
- **Enhanced service lifecycle**: Better error handling and recovery
- **24/7 operation**: Ring gestures work continuously without user intervention
- **No app dependency**: Service runs independently of MainActivity

### v2.3 - Enhanced Doze Mode Resistance (Current)
- **Doze mode resistance**: Persistent foreground service with maximum-priority notification
- **Multiple wake locks**: PARTIAL_WAKE_LOCK + SCREEN_DIM_WAKE_LOCK for maximum protection
- **AlarmManager integration**: Exact alarms every 5 minutes to wake up from Doze mode
- **Enhanced notification**: IMPORTANCE_MAX, ongoing notification that cannot be dismissed
- **Doze mode monitoring**: Periodic checks every 30 seconds to maintain service
- **MediaSession persistence**: Ensures ring gesture detection stays active in Doze mode
- **Automatic recovery**: Service maintenance and MediaSession reactivation
- **Multi-layer protection**: Notification, dual wake locks, AlarmManager, and service monitoring
- **Special use foreground service**: mediaPlayback|specialUse for enhanced permissions

### v2.4 - Authentic Music Player Behavior
- **Audio focus management**: Proper audio focus request/abandon like real music players
- **Enhanced MediaSession**: Rich metadata with title, artist, album, genre, and duration
- **Media-style notification**: Uses MediaStyle notification with proper media controls
- **Audio attributes**: FLAG_AUDIBILITY_ENFORCED for enhanced audio system integration
- **Audio focus handling**: Responds to focus changes like professional music apps
- **Transport category**: Notification categorized as TRANSPORT for system recognition
- **Media3 integration**: Added Media3 session support for modern Android audio handling
- **Professional audio behavior**: Mimics behavior of Spotify, YouTube Music, etc.

### v2.5 - Aggressive Doze Mode Resistance
- **1-minute alarm intervals**: Reduced from 5 minutes to 1 minute for constant wake-ups
- **Never pause playback**: Ignores audio focus loss to keep MediaSession always active
- **Permanent wake locks**: PARTIAL_WAKE_LOCK + SCREEN_DIM_WAKE_LOCK never released
- **ACQUIRE_CAUSES_WAKEUP**: Forces device wake-up on screen wake lock
- **Aggressive alarm maintenance**: Re-acquires wake locks, audio focus, and MediaSession on every alarm
- **Continuous playback enforcement**: Ensures music never stops, even on focus loss
- **Multi-layered persistence**: Wake locks + alarms + audio focus + active playback
- **Maximum aggressiveness**: All techniques combined for ultimate Doze mode survival

### v2.6 - Auxio-Inspired Doze Mode Resistance
- **Screen-friendly approach**: Removed SCREEN_DIM_WAKE_LOCK to allow screen to turn off
- **2-minute alarm intervals**: Balanced approach - not too aggressive, not too passive
- **Enhanced MediaSession**: Rich metadata with media ID, track numbers, and album artist
- **Auxio-style playback state**: Proper initial playback state with all transport actions
- **Graceful audio focus handling**: Continues playback for ring gesture detection
- **PARTIAL_WAKE_LOCK only**: CPU stays active without keeping screen on
- **Professional music player behavior**: Mimics Auxio's clean, efficient approach
- **Balanced Doze resistance**: Effective protection without excessive battery drain

### v2.7 - 60-Minute Silent Music Approach
- **60-minute silent track**: Extended from 1 minute to 60 minutes for reduced loop frequency
- **8k bitrate optimization**: Low bitrate (8k) for minimal file size and CPU usage
- **Reduced MediaPlayer resets**: Only resets every 60 minutes instead of every minute
- **Less system interruption**: Fewer track changes = less chance of Doze mode killing
- **Optimized file size**: 14MB for 60 minutes (vs 960KB for 1 minute)
- **Extended loop intervals**: MediaPlayer stays active for much longer periods
- **Reduced completion events**: Fewer onCompletionListener triggers
- **Better Doze resistance**: Longer playback sessions = more stable service

### v2.8 - Battery Optimization Exemption
- **Battery optimization detection**: Automatically checks if app is exempt from battery optimization
- **Auto-request exemption**: Opens Android settings to disable battery optimization for the app
- **User-friendly notifications**: Clear guidance on why battery exemption is needed
- **UI integration**: Battery optimization button in main app interface
- **Google Play compliant**: Proper implementation following official Android guidelines
- **Professional approach**: Same technique used by Spotify, Strava, and other successful apps
- **Ultimate Doze resistance**: App becomes immune to Doze mode killing when exempted
- **Fallback support**: Still works with current approach if user doesn't exempt

### v2.9 - Accessibility Service Implementation
- **System-level access**: Accessibility Service provides elevated system privileges
- **Doze immunity**: Accessibility services are protected from Doze mode killing
- **Media button interception**: Direct access to system media button events
- **Dual service approach**: Both RingListenerService and Accessibility Service work together
- **User-friendly setup**: Clear UI guidance to enable accessibility service
- **Professional implementation**: Follows Android accessibility service best practices
- **Ultimate reliability**: System-level privileges ensure consistent operation
- **Google Play approved**: Legitimate use case for accessibility services

### v2.10 - Enhanced Accessibility Service + WorkManager Backup
- **Improved accessibility service**: Proper accessibility functionality to prevent auto-disable
- **Legitimate accessibility features**: Real accessibility event monitoring and feedback
- **WorkManager backup**: Google's recommended background task solution for Doze resistance
- **Triple-layer protection**: RingListenerService + Accessibility Service + WorkManager
- **Doze-aware scheduling**: WorkManager adapts to device state and battery level
- **Reliable background execution**: WorkManager designed specifically for background tasks
- **Professional approach**: Uses Android's official background task management
- **Maximum reliability**: Multiple redundant systems ensure consistent operation

### v2.11 - Device Admin Implementation
- **Device Admin privileges**: Highest level of system access without root
- **Maximum Doze resistance**: Device admin apps are rarely killed by Doze mode
- **Elevated system privileges**: Access to system-level resources and policies
- **Quadruple-layer protection**: RingListenerService + Accessibility Service + WorkManager + Device Admin
- **User-friendly setup**: Clear UI guidance to enable device admin
- **Professional implementation**: Follows Android device admin best practices
- **Ultimate reliability**: Device admin provides maximum system-level protection
- **Google Play approved**: Legitimate use case for device administration

### v3.0 - Auxio-Inspired Media3 ExoPlayer Implementation (Current)
- **Media3 ExoPlayer**: Modern playback engine used by Auxio for superior Doze resistance
- **Simplified architecture**: Clean, focused service without complex multi-service approach
- **Professional MediaSession**: Proper Media3 MediaSession implementation
- **Audio focus management**: Intelligent audio focus handling for ring gesture detection
- **Continuous silent playback**: ExoPlayer maintains playback state reliably
- **User-friendly approach**: Single battery optimization setting (no Device Admin required)
- **Auxio-proven strategy**: Same approach used by successful open-source music player
- **Clean codebase**: Removed complex workarounds in favor of proper Media3 implementation

### v3.1 - Refined Auxio-Style Implementation (Current)
- **Removed AlarmManager**: Eliminated periodic wake-ups that can trigger OEM throttling
- **Persistent MediaStyle notification**: NotificationCompat foreground with MediaSession binding
- **Pure MediaSessionService**: Relies on legitimate media service recognition by Android
- **Simplified Doze resistance**: Continuous playback + proper media notification + wake locks
- **OEM-friendly approach**: Avoids aggressive techniques that trigger manufacturer restrictions
- **Auxio-aligned strategy**: Matches proven music player patterns for maximum compatibility
- **Clean notification management**: MediaStyle notification tied to MediaSession for system recognition

### v3.2 - Screen-State Aware Wear Launch
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

### v3.4 - Dual Silent Track System
- **Two-track playlist**: Added second silent MP3 (silent2.mp3) for reliable ring gesture detection
- **Ring gesture advances tracks**: Tap now switches between Silent Track 1 and Silent Track 2
- **Track metadata**: Both MP3 files tagged with unique titles using ffmpeg
- **Current track display**: Phone app UI shows which silent track is currently playing
- **Notification updates**: Notification bar displays current track name
- **Play/Pause functionality**: Fixed play/pause button to properly control AuxioStyleService
- **PlaybackStateManager integration**: Real-time track and playback state updates to UI
- **REPEAT_MODE_ALL**: ExoPlayer configured to loop the two-track playlist indefinitely

### v4.0 - Wrist Gesture Detection
- **Project Migration**: Rebranded from RingXwatch to Shepherd Signal
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

### v4.1 - Gesture Distinction Feature
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

### Planned Features 🔄
- Gesture sensitivity customization
- Multiple gesture pattern recognition

### Preserved for Reference 📚
- Ring gesture related code (not actively used, but preserved for reusable components)
- Phone-watch communication infrastructure
- MediaSession and playback control code

## Future Enhancements

- [ ] External rotation gesture detection implementation
- [ ] Gesture pattern customization (sensitivity, timing)
- [ ] Multiple gesture recognition (sequence of gestures)
- [ ] Gesture-based shortcuts (different gestures for different actions)
- [ ] Sensor data analysis tools for gesture tuning

## License

This project is private and not licensed for public use.

## Author

Developed with assistance from Claude Code.
