# System App Installation Guide

If all other approaches fail, installing RingXwatch as a system app provides the ultimate Doze resistance.

## Why System Apps Work

- **Highest Priority**: System apps are rarely killed by Doze mode
- **System Partition**: Installed in `/system/app/` or `/system/priv-app/`
- **Root Privileges**: Can access system-level resources
- **Doze Immunity**: Protected from Android's power management

## Requirements

- **Root Access**: Device must be rooted
- **Custom Recovery**: TWRP or similar recovery
- **System Partition Write**: Ability to write to `/system/`

## Installation Steps

### Method 1: Using ADB (Recommended)

1. **Enable Developer Options**:
   - Go to Settings > About Phone
   - Tap "Build Number" 7 times
   - Go to Settings > Developer Options
   - Enable "USB Debugging"

2. **Connect to Computer**:
   ```bash
   adb devices
   adb shell
   su
   ```

3. **Mount System Partition**:
   ```bash
   mount -o rw,remount /system
   ```

4. **Copy APK to System**:
   ```bash
   cp /sdcard/RingXwatch.apk /system/priv-app/RingXwatch/
   chmod 644 /system/priv-app/RingXwatch/RingXwatch.apk
   chown root:root /system/priv-app/RingXwatch/RingXwatch.apk
   ```

5. **Reboot Device**:
   ```bash
   reboot
   ```

### Method 2: Using TWRP Recovery

1. **Boot into TWRP Recovery**
2. **Mount System Partition**
3. **Copy APK to System**:
   - Navigate to `/system/priv-app/`
   - Create folder `RingXwatch`
   - Copy APK to folder
   - Set permissions: 644
4. **Reboot Device**

### Method 3: Using Magisk Module

1. **Create Magisk Module**:
   ```bash
   mkdir -p /sdcard/ringxwatch_system
   mkdir -p /sdcard/ringxwatch_system/system/priv-app/RingXwatch
   cp RingXwatch.apk /sdcard/ringxwatch_system/system/priv-app/RingXwatch/
   ```

2. **Create module.prop**:
   ```
   id=ringxwatch_system
   name=RingXwatch System App
   version=v1.0
   versionCode=1
   author=RingXwatch
   description=Install RingXwatch as system app for ultimate Doze resistance
   ```

3. **Install via Magisk Manager**

## Verification

After installation, verify the app is a system app:

```bash
adb shell
pm list packages -s | grep ringxwatch
```

Should show: `package:com.g150446.ringxwatch`

## Benefits

- ✅ **Ultimate Doze Resistance**: System apps are rarely killed
- ✅ **Persistent Operation**: Runs with highest priority
- ✅ **System Privileges**: Access to system-level resources
- ✅ **Auto-Start**: Automatically starts on boot
- ✅ **Protected**: Cannot be easily uninstalled

## Risks

- ⚠️ **Requires Root**: Device must be rooted
- ⚠️ **System Modification**: Modifies system partition
- ⚠️ **OTA Updates**: May be removed during system updates
- ⚠️ **Warranty**: May void device warranty

## Alternative: Use Device Admin

If you cannot root your device, the **Device Admin** approach (implemented in v2.11) provides the highest level of privileges without root access.

Device Admin benefits:
- ✅ **Elevated Privileges**: Higher than regular apps
- ✅ **Doze Resistance**: Better protection than standard services
- ✅ **No Root Required**: Works on non-rooted devices
- ✅ **Google Play Approved**: Legitimate use case
