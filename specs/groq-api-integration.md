# Groq API Integration Specification

## Overview

Voice Harness can use Groq's cloud API for STT and LLM when the **Cloud (Groq)** profile is selected in Model Settings. Local profiles (Gemma / Qwen+LFM) remain available.

Canonical operational doc: [`documents/groq_cloud.md`](../documents/groq_cloud.md).

## API Endpoints

### 1. Whisper Audio Transcription

- **Endpoint**: `https://api.groq.com/openai/v1/audio/transcriptions`
- **Method**: POST
- **Content-Type**: `multipart/form-data`
- **Model**: `whisper-large-v3-turbo`
- **response_format**: `json` (includes `text` and `language`)

### 2. Chat Completions

- **Endpoint**: `https://api.groq.com/openai/v1/chat/completions`
- **Method**: POST
- **Content-Type**: `application/json`
- **Model**: `openai/gpt-oss-120b`
- **Tools**: `set_reminder` (function calling)

## Authentication

- **Format**: `gsk_...`
- **Storage**: phone SharedPreferences only
  - file: `groq_prefs`
  - key: `groq_api_key`
- **Header**: `Authorization: Bearer <api_key>`

No Wear OS / Data Layer sync (single phone app).

## Implementation

| Component | Role |
|-----------|------|
| `GroqVoiceAiBackend` | Implements `VoiceAiBackend` via OkHttp |
| `GroqPrefs` | API key get/set |
| `GroqChatRequestBuilder` | Chat request JSON + system prompt + tools |
| `OnDeviceProfile.GROQ` | Selectable profile |
| `OnDeviceAiFacade` | Routes to Groq when profile is GROQ |
| `VoiceProcessor` | Unchanged pipeline; consumes `TranscriptionResult` / `ChatResult` |

## Request notes

- Audio is WAV from BLE PCM (or other local capture paths).
- Chat uses conversation history from `ConversationSession`.
- Reminder tool calls are applied on-device by `VoiceProcessor` (same path as local backends).
