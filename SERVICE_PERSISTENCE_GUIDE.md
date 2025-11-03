# Service Persistence Guide - Keeping Silent Music Player Alive

## Problem
The phone app's silent music player service (`AuxioStyleService`) is being killed after ~5 minutes when the screen locks, preventing ring gesture detection.

## About Neo-Launcher Customization
**? NO - Customizing Neo-launcher will NOT help:**
- Launchers are regular apps, not system services
- They have the same battery restrictions as your app
- They cannot keep other apps' services alive
- The problem is Android's system-level Doze mode, not the launcher

## Implemented Solutions (Already in Code)

### ? Option 1: Enhanced Foreground Service Configuration
**Status: IMPLEMENTED**

- Added `specialUse` foreground service type to manifest
- Added `stopWithTask="false"` to prevent killing when app is removed from recent apps
- Enhanced notification with `FOREGROUND_SERVICE_IMMEDIATE` behavior (Android 14+)
- Service returns `START_STICKY` to auto-restart if killed

**Files modified:**
- `AndroidManifest.xml` - Service configuration
- `AuxioStyleService.kt` - Enhanced onStartCommand and notification

### ? Option 2: Periodic Service Maintenance
**Status: IMPLEMENTED**

- Runs every 4 minutes (before 5-minute kill threshold)
- Re-acquires wake lock if released
- Re-ensures foreground service status
- Restarts ExoPlayer if stopped (keeps MediaSession active)
- Re-requests audio focus

**How it works:**
- Prevents Doze mode from killing service by maintaining activity
- Runs in coroutine background scope
- Logs all maintenance actions

### ? Option 3: Screen State BroadcastReceiver
**Status: IMPLEMENTED**

- Monitors screen ON/OFF/UNLOCK events
- Re-activates service when screen unlocks
- Maintains wake lock when screen locks
- Ensures ExoPlayer stays active

**Events monitored:**
- `ACTION_SCREEN_ON` - Screen turned on
- `ACTION_SCREEN_OFF` - Screen turned off
- `ACTION_USER_PRESENT` - Screen unlocked

### ? Option 4: Enhanced Wake Lock Management
**Status: IMPLEMENTED**

- Extended wake lock timeout to 10 hours (safety limit)
- Re-acquired during periodic maintenance
- Maintained on screen lock events

### ? Option 5: Enhanced Notification
**Status: IMPLEMENTED**

- Added `FOREGROUND_SERVICE_IMMEDIATE` for Android 14+
- Added `CATEGORY_SERVICE`
- Maintains MediaStyle notification with MediaSession

### ? Option 6: onTaskRemoved Handler
**Status: IMPLEMENTED**

- Handles case when app is swiped away from recent apps
- Explicitly restarts service if needed
- Works with `START_STICKY` for double protection

## Additional Options (Not Yet Implemented)

### Option A: WorkManager Backup Service
**Complexity: Medium | Effectiveness: High**

Use Google's WorkManager to periodically check and restart the service:
```kotlin
// PeriodicWorkRequest every 3 minutes
// Checks if service is running, restarts if needed
```

**Pros:**
- Google's official solution
- Survives Doze mode
- Reliable restart mechanism

**Cons:**
- Additional dependency
- May have delays (up to 15 minutes in Doze)

### Option B: AlarmManager with Exact Alarms
**Complexity: Low | Effectiveness: Medium**

**Note:** Already removed from code to avoid OEM throttling

Use `setExactAndAllowWhileIdle()` to wake up every 4 minutes:
```kotlin
alarmManager.setExactAndAllowWhileIdle(
    AlarmManager.RTC_WAKEUP,
    System.currentTimeMillis() + 4 * 60 * 1000,
    pendingIntent
)
```

**Pros:**
- Direct system integration
- Can wake from deep Doze

**Cons:**
- Some OEMs throttle this aggressively
- Already tried and removed

### Option C: Accessibility Service Backup
**Complexity: Medium | Effectiveness: High**

**Note:** Already implemented but may need enhancement

Your app already has `RingAccessibilityService`. Enhance it to:
- Monitor service status
- Restart service if killed
- Use accessibility service privileges

**Pros:**
- High system privileges
- Protected from Doze
- Already in codebase

**Cons:**
- Requires user to enable accessibility service
- More complex

### Option D: Device Admin Backup
**Complexity: Medium | Effectiveness: High**

**Note:** Already implemented but may need enhancement

Your app already has `RingDeviceAdminReceiver`. Use it to:
- Monitor service health
- Restart if killed
- Use device admin privileges

**Pros:**
- Highest app-level privileges
- Very protected from Doze

**Cons:**
- Requires user to enable device admin
- More intrusive to users

### Option E: ExoPlayer Continuous Playback
**Complexity: Low | Effectiveness: High**

**Note:** Already implemented but verify it's working

Ensure ExoPlayer:
- Never pauses (even on audio focus loss)
- Immediately restarts if playback stops
- Uses infinite loop mode

**Current status:** Already implemented with loop

## User Settings to Check

### Critical Settings (User Must Configure)

1. **Battery Optimization Exemption**
   - Settings ? Apps ? RingXwatch ? Battery ? Unrestricted
   - OR use the button in your app's UI
   - **This is the MOST IMPORTANT setting**

2. **App Standby Buckets**
   - Settings ? Apps ? RingXwatch ? Battery
   - Ensure app is in "Active" or "Working Set" bucket

3. **Background App Restrictions**
   - Settings ? Apps ? RingXwatch ? Battery ? Background restriction
   - Should be "Allow"

4. **Auto-start / Startup Manager** (OEM-specific)
   - Some OEMs (Xiaomi, Huawei, Oppo) have additional startup managers
   - Enable "Auto-start" for RingXwatch

5. **Doze Mode Whitelist** (if available)
   - Developer options ? Doze mode whitelist
   - Add RingXwatch

### Neo-Launcher Specific Settings

While customizing Neo-launcher won't help, check these in Neo-launcher:
- Auto-start settings (if Neo has startup manager)
- Battery optimization (if Neo can manage it)
- But remember: **These are just UI wrappers around system settings**

## Testing & Verification

### How to Test Service Persistence

1. **Start service** - Check notification appears
2. **Lock screen** - Wait 10 minutes
3. **Unlock screen** - Check notification still visible
4. **Check logs** - Look for periodic maintenance messages
5. **Test ring gesture** - Should still work after 10+ minutes

### Logcat Commands

```bash
# Monitor service lifecycle
adb logcat | grep -E "AuxioStyleService|Service.*started|Service.*stopped"

# Monitor periodic maintenance
adb logcat | grep -E "Periodic maintenance|Service.*kept alive"

# Monitor screen events
adb logcat | grep -E "Screen.*unlocked|Screen.*locked"
```

## Recommended Implementation Priority

1. ? **Already Implemented:**
   - Periodic maintenance (every 4 min)
   - Screen state receiver
   - Enhanced wake locks
   - Special use foreground service

2. **Next to Implement (if still needed):**
   - WorkManager backup (Option A)
   - Enhanced Accessibility Service monitoring (Option C)

3. **Last Resort:**
   - Device Admin monitoring (Option D)
   - Aggressive AlarmManager (Option B) - but may be throttled

## Summary

**Current Implementation Status:**
- ? Multiple persistence mechanisms in place
- ? Periodic maintenance prevents 5-minute kill
- ? Screen state monitoring for unlock recovery
- ? Enhanced foreground service configuration

**About Neo-Launcher:**
- ? Customizing Neo-launcher will NOT help
- The issue is Android system-level, not launcher-level
- Focus on app-level solutions (already implemented)

**Next Steps:**
1. Test current implementation
2. If service still dies, add WorkManager backup
3. Ensure user disables battery optimization
4. Check OEM-specific settings (auto-start, etc.)
