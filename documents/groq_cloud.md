# Cloud (Groq) バックエンド

## 概要

音声認識と/または応答生成を **Groq API** で行うクラウドバックエンド。  
ASR（Whisper）と LLM（Chat）は独立選択できる（例: ASR=Groq、LLM=OpenRouter または Gemma）。

| 役割 | 実装 | モデル |
|------|------|--------|
| STT | Whisper API | `whisper-large-v3-turbo` |
| LLM | Chat Completions | `openai/gpt-oss-120b`（`GroqChatRequestBuilder`） |
| リマインダー | function calling `set_reminder` | 端末側でアラーム登録 |

TTS は従来どおり Android `TextToSpeech`（または Z100 表示）。  
画面コンテキストはテキストのみ一時プロンプトへ添付可能（JPEG は送らない）。

## 設定

1. ホーム → **モデル設定**
2. **音声認識** で Cloud (Groq Whisper)、および/または **応答モデル** で Cloud (Groq Chat) を選択
3. `GROQ_API_KEY` を入力して **API キーを保存**
4. （任意）**モデルを読み込む / 接続確認**

API キーは端末内 SharedPreferences のみ:

- prefs: `groq_prefs`
- key: `groq_api_key`

Wear OS 同期は行わない（単一 phone アプリ構成）。

## コード構成

```
VoiceProcessor
  → OnDeviceAiFacade（ASR / LLM 独立）
       ├─ SttBackendId.GROQ → GroqVoiceAiBackend.transcribe
       └─ LlmBackendId.GROQ → GroqVoiceAiBackend.chat
```

| ファイル | 役割 |
|---|---|
| `GroqVoiceAiBackend.kt` | OkHttp で Whisper + Chat。`VoiceAiBackend` 実装 |
| `GroqPrefs.kt` | API キーの読み書き |
| `GroqChatRequestBuilder.kt` | Chat JSON（system prompt / tools / 画面テキスト） |
| `GroqSettingsActivity.kt` | モデル設定 UI |

## 実行フロー

```text
WAV
  → Groq Whisper（language 付き JSON）
  → AsrTextFilter
  → Groq Chat（tools: set_reminder、任意の画面テキスト）
  → TTS / Z100
```

## ログ

```bash
adb logcat -s VoiceProcessor:D GroqVoiceAiBackend:D OnDeviceAiFacade:D ModelManager:D
```

## 注意

- API 利用料・レート制限は Groq 側の契約に従う
- OpenRouter との併用は LLM 側のみ切替（[`openrouter.md`](openrouter.md)）
