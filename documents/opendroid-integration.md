# OpenDroid integration

Voice Harness remains the product repository, package identity, and application shell. OpenDroid
concepts are integrated behind narrow interfaces instead of merging the unrelated repositories or
shipping two application modules.

## Runtime boundaries

```text
harness-node PCM -> VoiceProcessor -> AssistantGateway -> VoiceAiBackend
                                                   |-> local/cloud LLM
                                                   `-> shared TTS output

Android ROLE_ASSISTANT
  -> VoiceInteractionSession (UI disabled)
  -> HarnessAssistantActivity (bottom sheet)
  -> AssistantSessionController
  -> AssistantGateway (+ optional ScreenContext)
  -> STT/LLM backends (independent) / OpenRouter
  -> shared TTS (voice input only)
```

`AssistantGateway` owns headless conversation state for both HarnessNode and the digital assistant.
HarnessNode never receives screen context. The assistant sheet keeps multi-turn memory only while
open; closing discards in-memory history and screen snapshots.

STT and LLM backends are selected independently (`SttBackendId` / `LlmBackendId`). OpenRouter is
LLM-only and requires an explicit API key + model choice. API keys for OpenRouter are stored as
Keystore-backed AES-GCM ciphertext.

`BleConnectionService` remains the long-lived runtime because it already owns the BLE connection,
model lifecycle, response state, and TTS engine.

## Implementation plan (canonical)

See [`voice-harness-android-openrouter-plan.md`](./voice-harness-android-openrouter-plan.md).

## Source policy

- Repository and application ID remain `voice-harness-android` and
  `com.g150446.voiceharness`.
- OpenDroid is a reference implementation, not a Git submodule or a second `:app` module.
- Preserve the existing Harness UI, BLE protocol, and upgrade path.

## Device acceptance

- Power-button long-press opens the bottom sheet without auto-listening.
- Mic input speaks responses; text input does not TTS by default.
- Screen context chip works only when AssistStructure/screenshot arrived; OFF strips context.
- HarnessNode path stays headless with no screen context.
- Cancel/close drops late results and cancels OpenRouter HTTP calls.
- Locked keyguard does not show prior conversation or screen content.

### 2026-08-24 result (headless baseline)

Validated on a Motorola razr 50s running Android 16 / API 36:

- Android accepted Voice Harness as `ROLE_ASSISTANT` and bound its interaction and recognition services.
- Keyguard launch capability was reported as enabled after reloading the role metadata.
- In forced deep idle, HarnessNode remained connected and delivered recording events and PCM.
- The utterance "入力テスト" completed Groq Whisper, chat, and local Android TTS in 1.856 seconds.
- The BLE service changed foreground types from `connectedDevice` (16) to
  `connectedDevice|mediaPlayback` (18) only during TTS, then returned to 16.
- No process crash or foreground-service security exception occurred.

The device's original OpenDroid assistant role and simulated battery/idle state were restored after
the test.
