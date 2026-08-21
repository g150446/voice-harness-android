# Cloud (Groq) バックエンド

## 概要

音声認識と応答生成を **Groq API** で行うクラウドプロファイル。  
オンデバイス（Gemma / Qwen ASR + LFM）と設定画面から切り替え可能。

| 役割 | 実装 | モデル |
|------|------|--------|
| STT | Whisper API | `whisper-large-v3-turbo` |
| LLM | Chat Completions | `openai/gpt-oss-120b`（`GroqChatRequestBuilder`） |
| リマインダー | function calling `set_reminder` | 端末側でアラーム登録 |

TTS は従来どおり Android `TextToSpeech`（または Z100 表示）。

## 設定

1. ホーム → **モデル設定**
2. プロファイル **Cloud (Groq)** を選択
3. `GROQ_API_KEY` を入力して **API キーを保存**
4. （任意）**接続を確認**

API キーは端末内 SharedPreferences のみ:

- prefs: `groq_prefs`
- key: `groq_api_key`

Wear OS 同期は行わない（単一 phone アプリ構成）。

## コード構成

```
VoiceProcessor
  → OnDeviceAiFacade（プロファイル切替）
       ├─ GEMMA → GemmaOnDeviceBackend
       ├─ QWEN  → QwenOnDeviceBackend
       └─ GROQ  → GroqVoiceAiBackend   ← 本ドキュメント
```

| ファイル | 役割 |
|----------|------|
| `GroqVoiceAiBackend.kt` | OkHttp で Whisper + Chat。`VoiceAiBackend` 実装 |
| `GroqPrefs.kt` | API キーの読み書き |
| `GroqChatRequestBuilder.kt` | Chat JSON（system prompt / tools） |
| `OnDeviceProfile.GROQ` | プロファイル enum |
| `GroqSettingsActivity.kt` | モデル設定 UI（Groq 選択時にキー入力） |

## エンドポイント

- `POST https://api.groq.com/openai/v1/audio/transcriptions`
- `POST https://api.groq.com/openai/v1/chat/completions`

`Authorization: Bearer <api_key>`

## 実行フロー

```text
BLE PCM → VAD → WAV
  → Groq Whisper（language 付き JSON）
  → ConversationSession
  → Groq Chat（tools: set_reminder）
  → VoiceProcessor が tool call を処理 / TTS or Z100
```

- クラウド時は ASR 語彙 2 パスをスキップする
- 初回モデルロード（30–60 秒）メッセージは出さない
- ネットワーク必須。キー未設定時は `ensureReady` が失敗し設定誘導メッセージを出す

## ログ

```bash
adb logcat -s VoiceProcessor:D GroqVoiceAiBackend:D OnDeviceAiFacade:D ModelManager:D
```

## 注意

- オフラインでは失敗する
- API 利用料・レート制限は Groq 側の契約に従う
- キーを Git やログに出さない
