# Agent notes

At the start of each session, read README.md to understand the purpose and structure of this project.

## Build and install on the device

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug -q
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
```

After installing over ADB, always open the app so it is running for the user's on-device check
(the install stops the running app and its service):

```sh
adb shell am start -n com.g150446.voiceharness/.MainActivity
```

When several devices are attached (for example wireless ADB), pass `-s <serial>` to each adb command.
