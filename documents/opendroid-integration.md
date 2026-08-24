# OpenDroid integration

Voice Harness remains the product repository, package identity, and application shell. OpenDroid
concepts are integrated behind narrow interfaces instead of merging the unrelated repositories or
shipping two application modules.

## Runtime boundaries

```text
harness-node PCM -> VoiceProcessor -> AssistantGateway -> VoiceAiBackend
                                                   |-> local/cloud LLM
                                                   `-> shared TTS output

Android ROLE_ASSISTANT -> VoiceInteractionSession -> AssistantGateway -> shared TTS output
```

`AssistantGateway` owns headless conversation state. Neither BLE input nor the system-assistant
entry point requires an Activity. The harness path uses the stable `harness-node` conversation ID;
each system invocation session receives its own ID.

`BleConnectionService` remains the long-lived runtime because it already owns the BLE connection,
model lifecycle, response state, and TTS engine. It runs as `connectedDevice` and adds
`mediaPlayback` only while phone audio is playing. A bounded processing wake lock covers the gap
between a screen-off assistant invocation and the start of playback.

## Source policy

- Repository and application ID remain `voice-harness-android` and
  `com.g150446.voiceharness`.
- OpenDroid is a reference implementation, not a Git submodule or a second `:app` module.
- Record each future OpenDroid-derived change here with its source commit and affected subsystem.
- Preserve the existing Harness UI, BLE protocol, model profiles, and upgrade path.

## Follow-up module extraction

The first integration keeps sources in `:app` to minimize risk to the native Qwen/LiteRT packaging.
Once the new assistant path has passed device acceptance testing, extract packages in this order:

1. `core:common` and `core:voice`
2. `core:agent` and `core:data`
3. `feature:harness`, `feature:system-assistant`, and `feature:automation`

Every extraction must be behavior-preserving and leave `:app:assembleDebug` green. Feature modules
must communicate through `AssistantGateway`/`SpeechOutput`-style contracts rather than depend on
one another.

## Device acceptance

- Invoke while unlocked, locked, immediately after screen-off, and in forced Doze.
- Verify local TTS, Bluetooth audio, and smart-glasses routing.
- Kill and restart the process, reconnect harness-node, and invoke again.
- Confirm protected device actions request unlock rather than attempting to bypass the keyguard.
