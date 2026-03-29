# アーキテクチャ詳細

## コンポーネント構成

```
┌─────────────────────────────────────────────┐
│                MainActivity                  │
│  (Jetpack Compose UI / 権限管理)             │
└──────────────────────┬──────────────────────┘
                       │ viewModels()
┌──────────────────────▼──────────────────────┐
│              VoiceViewModel                  │
│  ・録音状態管理 (VoiceState)                  │
│  ・BLE イベント → 録音制御                    │
│  ・Silero VAD / FFT fallback / rescue 判定    │
│  ・Groq API 呼び出し (OkHttp)               │
│  ・Android TTS                              │
└──────────┬──────────────────────────────────┘
           │ StateFlow / SharedFlow (companion object)
┌──────────▼──────────────────────────────────┐
│           BleConnectionService               │
│  (ForegroundService / フォアグラウンド通知)   │
│  ・BleManager のライフサイクル管理            │
│  ・Flow を companion object 経由で公開        │
└──────────┬──────────────────────────────────┘
           │
┌──────────▼──────────────────────────────────┐
│               BleManager                    │
│  ・BLE スキャン・接続・GATT                  │
│  ・パケット解析 (音声 / イベント)             │
│  ・指数バックオフ再接続                       │
└─────────────────────────────────────────────┘

┌─────────────────────────────────────────────┐
│            BleSpeechDetector                │
│  ・PCM → Float 変換                         │
│  ・DC オフセット除去                         │
│  ・FFT ベースの帯域比解析                    │
│  ・Silero 異常時の fallback 判定             │
└─────────────────────────────────────────────┘
```

## 状態管理

### VoiceState

```
READY ──── 録音開始 ──▶ RECORDING ──── 停止 ──▶ TRANSCRIBING
  ▲                                                    │
  │                                                    ▼
  │◀─────── 読み上げ完了 ─── SPEAKING ◀──── RESPONDING
  │
  └◀─────────────────── ERROR
```

| 状態 | 説明 |
|---|---|
| `READY` | 待機中 |
| `RECORDING` | 録音中（BLE または電話マイク） |
| `TRANSCRIBING` | Groq Whisper API 呼び出し中 |
| `RESPONDING` | Groq Chat API 呼び出し中 |
| `SPEAKING` | TTS 読み上げ中 |
| `ERROR` | エラー発生 |

### BleConnectionState

`DISCONNECTED` → `SCANNING` → `CONNECTING` → `CONNECTED`

切断時は自動的に `SCANNING` へ戻り再接続を試みる。

## Flow アーキテクチャ

`BleManager` の Flow を `BleConnectionService` が中継し、`VoiceViewModel` が収集する。
ViewModel は Service より先に生成されることがあるため、Flow を Service インスタンスではなく **companion object** に置いて app ライフタイムで保持している。

```kotlin
// BleConnectionService companion object (app ライフタイム)
private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()
```

Service が起動したら BleManager の Flow を collect して companion object に中継する。

## BLE 録音フロー（ファームウェア主導）

```
nRF52840                        Android
    │── 0x01 (RecordingStarted) ──▶│
    │                               │ handleBleRecordingStarted()
    │                               │   isCollectingPcm = true
    │                               │   state = RECORDING
    │── [audio packets] ────────────▶│
    │                               │   pcmBuffer.write(packet.pcmData)
    │── 0x02 (RecordingStopped) ───▶│
    │                               │ handleBleRecordingStopped()
    │                               │   Silero VAD
    │                               │   FFT fallback / rescue
    │                               │   buildWavFile()
    │                               │   Groq Whisper API
    │                               │   Groq Chat API
    │                               │   TTS 読み上げ
```

Android 側からコマンド (`sendCommand`) を送る必要はない。ファームウェアがジェスチャー検知と録音タイミングを自律制御する。

## BLE 音声判定

BLE 音声は `VoiceViewModel.hasSpeechInPcm()` が担当し、次の順で判定する。

1. `SileroVad.kt` で 512 サンプルごとの推論を行う
2. `maxProb <= 0.01` など Silero が異常に低い確率へ張り付く場合は `BleSpeechDetector.kt` の FFT 判定へ切り替える
3. FFT 判定でも境界値だった場合は、`peakAfterDC` / `rmsAfterDC` / `maxBandRatio` を使った rescue 条件で BLE 音声を救済する

これにより、Silero モデルの不調や BLE マイク特有の低振幅音声があっても Groq 送信を止めにくくしている。

## API 通信

### Groq Whisper（文字起こし）

```
POST https://api.groq.com/openai/v1/audio/transcriptions
Authorization: Bearer {groq_api_key}
Content-Type: multipart/form-data

file: audio.wav  (BLE) or audio.m4a  (電話マイク)
model: whisper-large-v3-turbo
response_format: text
```

### Groq Chat（AI 応答）

```
POST https://api.groq.com/openai/v1/chat/completions
Authorization: Bearer {groq_api_key}
Content-Type: application/json

{
  "model": "openai/gpt-oss-120b",
  "messages": [{"role": "user", "content": "{transcribed_text}"}]
}
```

## WAV ファイル生成

BLE から受け取った生 PCM データに 44 バイトの WAV ヘッダを付加して一時ファイルに書き込む。

| パラメータ | 値 |
|---|---|
| フォーマット | PCM (format tag = 1) |
| チャンネル数 | 1 (モノラル) |
| サンプルレート | 16,000 Hz |
| ビット深度 | 16-bit |
| バイトオーダー | Little Endian |

送信完了後に `file.delete()` で一時ファイルを削除する。

## パーミッション

| パーミッション | API レベル | 用途 |
|---|---|---|
| `BLUETOOTH_SCAN` | 31+ | BLE スキャン |
| `BLUETOOTH_CONNECT` | 31+ | BLE 接続 |
| `ACCESS_FINE_LOCATION` | ≤30 | BLE スキャン（旧 API） |
| `RECORD_AUDIO` | all | 電話マイク録音 |
| `INTERNET` | all | Groq API 通信 |
| `FOREGROUND_SERVICE` | all | BleConnectionService |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | 34+ | ForegroundService タイプ指定 |
| `POST_NOTIFICATIONS` | 33+ | フォアグラウンドサービス通知 |

## GroqSettingsActivity

Groq API キーを `SharedPreferences("groq_prefs")` に保存する。  
キー名: `groq_api_key`

VoiceViewModel は毎回 API 呼び出し時に SharedPreferences から読み出す（キャッシュなし）。
