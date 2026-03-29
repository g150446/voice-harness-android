# Phone-Watch Communication Protocol Specification

## Overview

Harness Voice uses the Wearable Data Layer API from Google Play Services to enable bidirectional communication between the Android phone app and Wear OS watch app. This specification defines the message protocols, data synchronization, and connection management.

## Architecture

```
Phone App                          Wear OS App
    |                                    |
    | MessageClient                      | WearableMessageListenerService
    | NodeClient                         |
    | DataClient                         | DataClient
    |                                    |
    |-------- Message (/launch_app)---->|
    |                                    |
    |<------- Data (/settings) -------->|
```

## Communication Channels

### 1. Message API (Phone → Watch)
- **Purpose**: Send commands from phone to watch
- **Protocol**: Fire-and-forget messaging
- **Use Cases**: Launch watch app, send control commands

### 2. Data API (Bidirectional)
- **Purpose**: Sync configuration data
- **Protocol**: Replicated data store
- **Use Cases**: Groq API key synchronization

## Message Protocol

### Launch Watch App

**Message Path**: `/launch_app`

**Direction**: Phone → Watch

**Payload**: Empty byte array (`ByteArray(0)`)

**Phone Side** (MainActivity.kt):
```kotlin
val nodes = nodeClient.connectedNodes.await()
nodes.forEach { node ->
    messageClient.sendMessage(
        node.id,
        "/launch_app",
        ByteArray(0)
    ).await()
}
```

**Watch Side** (WearableMessageListenerService.kt):
```kotlin
override fun onMessageReceived(messageEvent: MessageEvent) {
    if (messageEvent.path == "/launch_app") {
        // Check display state
        // Launch MainActivity if screen is ON
    }
}
```

**Behavior**:
1. Phone sends message to all connected nodes
2. Watch service receives message
3. Service checks display state via `DisplayManager`
4. **Only launches if screen is ON** (interactive or ambient)
5. **Does NOT wake screen** if screen is OFF
6. Launches with flags: `NEW_TASK | CLEAR_TOP | SINGLE_TOP | BROUGHT_TO_FRONT`

**Expected Response Time**: < 500ms when watch screen is on

**Error Handling**:
- No connected nodes: Status message "No watch connected"
- Exception during send: Status message with error details
- Watch screen off: Silently ignored by watch service

## Data Synchronization Protocol

### Groq API Key Sync

**Data Path**: `/settings`

**Direction**: Phone → Watch (replicated)

**Data Structure**:
```kotlin
PutDataMapRequest.create("/settings").apply {
    dataMap.putString("groq_api_key", apiKey)
    dataMap.putLong("updated_at", System.currentTimeMillis())
}.asPutDataRequest().setUrgent()
```

**Phone Side** (GroqSettingsActivity.kt):
```kotlin
// Save locally
prefs.edit { putString("groq_api_key", apiKey) }

// Sync to watch
val putReq = PutDataMapRequest.create("/settings").apply {
    dataMap.putString("groq_api_key", apiKey)
    dataMap.putLong("updated_at", System.currentTimeMillis())
}.asPutDataRequest().setUrgent()

dataClient.putDataItem(putReq).await()
```

**Watch Side** (MainActivity.kt):
```kotlin
private val dataClient: DataClient = Wearable.getDataClient(this)
private val onDataChangedListener = OnDataChangedListener { dataEvents ->
    dataEvents.forEach { event ->
        if (event.type == DataEvent.TYPE_CHANGED &&
            event.dataItem.uri.path == "/settings") {
            val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
            val apiKey = dataMap.getString("groq_api_key")
            // Store locally in WearGroqPrefs
        }
    }
}
```

**Synchronization Guarantees**:
- **Eventual Consistency**: Data eventually synced to all connected devices
- **Automatic Retry**: Play Services handles retry on failure
- **Persistence**: Data persists across app restarts
- **Urgency Flag**: High-priority sync for immediate updates

## Node Discovery

### Connected Nodes Query

**API**: `NodeClient.getConnectedNodes()`

**Returns**: `Task<List<Node>>`

**Node Properties**:
```kotlin
data class Node(
    val id: String,           // Unique node identifier
    val displayName: String,  // Human-readable name
    val isNearby: Boolean     // Physical proximity
)
```

**Usage Pattern**:
```kotlin
val nodes = nodeClient.connectedNodes.await()
if (nodes.isEmpty()) {
    // No watch connected
} else {
    // Send message to all nodes
    nodes.forEach { node ->
        messageClient.sendMessage(node.id, path, data)
    }
}
```

**Connection State**:
- Watches are considered connected when paired and Bluetooth is active
- Connection state can change dynamically (Bluetooth off, out of range, etc.)
- Always check for connected nodes before sending messages

## Watch Launch Behavior

### Display State Management

**Display States**:
1. **Interactive**: Screen on, app interactive
2. **Ambient**: Screen dimmed, watch face or ambient mode
3. **Off**: Screen completely off

**Launch Policy**:
```kotlin
val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
val displayState = display.state

when (displayState) {
    Display.STATE_ON,        // Interactive mode
    Display.STATE_DOZE,      // Ambient mode
    Display.STATE_DOZE_SUSPEND -> {
        // Launch app
        launchMainActivity()
    }
    Display.STATE_OFF -> {
        // Ignore launch request - respect user context
        Log.d(TAG, "Screen is off, ignoring launch")
    }
}
```

**Intent Flags**:
```kotlin
val intent = Intent(this, MainActivity::class.java).apply {
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    addFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT)
}
startActivity(intent)
```

**Flag Purposes**:
- `NEW_TASK`: Launch in new task (required for service)
- `CLEAR_TOP`: Clear activities above in stack
- `SINGLE_TOP`: Don't create duplicate if already on top
- `BROUGHT_TO_FRONT`: Bring existing activity to front if exists

### Power Management

**No Wake Locks on Watch**:
- Watch service does NOT acquire wake locks
- Does NOT wake screen from OFF state
- Respects user context and saves battery
- Only responds when user has screen on

**Phone Side**:
- No special power management needed
- Standard message sending via MessageClient

## Error Handling and Reliability

### Connection Failures

**No Connected Nodes**:
```kotlin
val nodes = nodeClient.connectedNodes.await()
if (nodes.isEmpty()) {
    statusMessage = "No watch connected"
    // Inform user via UI
}
```

**Message Send Failure**:
```kotlin
try {
    messageClient.sendMessage(node.id, path, data).await()
} catch (e: Exception) {
    Log.e(TAG, "Error sending message", e)
    statusMessage = "Error: ${e.message}"
}
```

### Data Sync Failures

**DataClient Error Handling**:
```kotlin
try {
    dataClient.putDataItem(putReq).await()
    statusMessage = "Saved and synced"
} catch (e: Exception) {
    statusMessage = "Error: ${e.message}"
    // Data saved locally, will retry sync automatically
}
```

**Retry Strategy**:
- Play Services automatically retries failed sync operations
- No manual retry logic needed
- Local storage ensures data not lost on sync failure

### Timeout Handling

**Message Timeout**:
- MessageClient operations use coroutine timeout
- Default: 5000ms
- On timeout: Exception thrown, caught by error handler

**Data Sync Timeout**:
- DataClient uses internal timeout
- Urgent flag reduces latency
- Automatic retry on timeout

## Service Lifecycle

### WearableMessageListenerService

**Manifest Declaration**:
```xml
<service
    android:name=".WearableMessageListenerService"
    android:enabled="true"
    android:exported="true">
    <intent-filter>
        <action android:name="com.google.android.gms.wearable.MESSAGE_RECEIVED" />
        <data android:scheme="wear" android:host="*" android:pathPrefix="/launch_app" />
    </intent-filter>
</service>
```

**Lifecycle**:
1. **Boot**: Service auto-starts via RECEIVE_BOOT_COMPLETED
2. **Runtime**: Always running in background
3. **Message Receipt**: `onMessageReceived()` called by Play Services
4. **Destroy**: Only on app uninstall or force stop

**Thread Model**:
- `onMessageReceived()` called on main thread
- Heavy operations must be offloaded to background thread
- Service must return quickly to avoid ANR

## Data Storage

### Phone Storage (SharedPreferences)

**File**: `groq_prefs.xml`

**Location**: `data/data/com.g150446.harnessvoice/shared_prefs/`

**Contents**:
```xml
<map>
    <string name="groq_api_key">gsk_...</string>
</map>
```

### Watch Storage (WearGroqPrefs)

**Implementation**: SharedPreferences wrapper

**File**: `groq_prefs.xml`

**Location**: `data/data/com.g150446.harnessvoice/shared_prefs/`

**API**:
```kotlin
object WearGroqPrefs {
    fun saveApiKey(context: Context, apiKey: String)
    fun getApiKey(context: Context): String
}
```

## Security Considerations

### API Key Protection

**Storage**:
- Stored in private SharedPreferences
- Not accessible by other apps
- Backed up via Android Auto Backup (encrypted)

**Transmission**:
- Data Layer API uses encrypted Bluetooth connection
- No additional encryption needed
- API key transmitted only between paired devices

**Access Control**:
- Only this app can read/write its preferences
- Only paired devices receive data sync
- No exposure to external networks

### Permissions Required

**Phone App**:
- `POST_NOTIFICATIONS` (Android 13+)
- `RECEIVE_BOOT_COMPLETED`

**Wear App**:
- `WAKE_LOCK` (legacy, not actively used)
- `RECEIVE_BOOT_COMPLETED`
- `RECORD_AUDIO` (for voice recording)
- `INTERNET` (for Groq API)

## Performance Characteristics

### Message Latency
- **Local (Bluetooth)**: 50-200ms
- **Network (Wi-Fi/Internet)**: 200-1000ms
- **Timeout**: 5000ms

### Data Sync Latency
- **Urgent Flag**: 100-500ms
- **Normal Priority**: 500-2000ms
- **Background Sync**: Up to 30 seconds

### Battery Impact
- **Message Sending**: Negligible (< 1% per 100 messages)
- **Data Sync**: Low (< 1% per 100 syncs)
- **Service Running**: Minimal (always-on service)

## Testing

### Manual Testing

**Phone to Watch Message**:
1. Ensure watch is paired and connected
2. Open phone app
3. Press "Launch Watch App" button
4. Watch should launch app (if screen is on)
5. Check debug log for confirmation

**Data Sync**:
1. Open Groq Settings in phone app
2. Enter API key
3. Press "Save & Sync to Watch"
4. Check status message for "Saved and synced"
5. On watch, verify API key is available

### Debugging

**Phone Logcat**:
```bash
adb logcat -s PhoneApp MainActivity GroqSettingsActivity
```

**Watch Logcat**:
```bash
adb -s <watch-serial> logcat -s WearableMessageListenerService MainActivity WearGroqPrefs
```

**Verify Connection**:
```kotlin
val nodes = nodeClient.connectedNodes.await()
nodes.forEach { node ->
    Log.d(TAG, "Connected node: ${node.displayName} (${node.id})")
}
```

## Future Enhancements

### Planned Features
- Bidirectional status sync (watch → phone)
- Voice transcription result sync to phone
- Error reporting from watch to phone
- Remote gesture sensitivity configuration

### Proposed Message Paths
- `/status` - Watch status updates
- `/transcription` - Transcription results
- `/error` - Error reporting
- `/config` - Configuration updates
