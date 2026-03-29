# アーキテクチャ詳細

## コンポーネント構成

```
┌─────────────────────────────────────────────┐
│                MainActivity                  │
│  (Jetpack Compose UI / BLE 接続操作)         │
└──────────────────────┬──────────────────────┘
                       │ viewModels()
┌──────────────────────▼──────────────────────┐
│              VoiceViewModel                  │
│  ・録音状態管理 (VoiceState)                  │
│  ・BLE イベント → 録音制御                    │
│  ・スキャン結果 / 選択中デバイス管理          │
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
│  ・手動切断後の再接続抑止                     │
│  ・優先デバイスの保存 / 次回起動時自動接続    │
│  ・パケット解析 (音声 / イベント)             │
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
| `RECORDING` | 録音中（BLE デバイス） |
| `TRANSCRIBING` | Groq Whisper API 呼び出し中 |
| `RESPONDING` | Groq Chat API 呼び出し中 |
| `SPEAKING` | TTS 読み上げ中 |
| `ERROR` | エラー発生 |

### BleConnectionState

`DISCONNECTED` → `SCANNING` → `CONNECTING` → `CONNECTED`

通常は `CONNECTED` から切断されたときに保存済みデバイスへ自動再接続を試みる。  
ただしユーザーが `Disconnect` を押した場合は `DISCONNECTED` のまま待機し、`Scan devices` から明示的に再接続するまで自動再接続しない。

## Flow アーキテクチャ

`BleManager` の Flow を `BleConnectionService` が中継し、`VoiceViewModel` が収集する。
ViewModel は Service より先に生成されることがあるため、Flow を Service インスタンスではなく **companion object** に置いて app ライフタイムで保持している。

```kotlin
// BleConnectionService companion object (app ライフタイム)
private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()
```

Service が起動したら BleManager の Flow を collect して companion object に中継する。  
中継対象には接続状態・音声パケット・BLE イベントに加えて、スキャン結果一覧と保存済み優先デバイスも含まれる。

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

Android 側から録音開始/停止コマンドを送る必要はない。ファームウェアがジェスチャー検知と録音タイミングを自律制御する。  
Android アプリ側の UI は BLE デバイスのスキャン・選択・接続・切断だけを担当する。  
スキャン結果はアプリ内で単一選択リストとして表示し、ユーザーは対象デバイスを選んで `Connect` する。

## BLE 音声判定

BLE 音声は `VoiceViewModel.hasSpeechInPcm()` が担当し、次の順で判定する。

1. `SileroVad.kt` で 512 サンプルごとの推論を行う
2. Silero が異常に低い確率へ張り付く場合だけでなく、通常推論でも音声比率が閾値未満だった場合は `BleSpeechDetector.kt` の FFT 判定で再評価する
3. FFT 判定でも境界値だった場合は、`peakAfterDC` / `rmsAfterDC` / `maxBandRatio` を使った rescue 条件で BLE 音声を救済する

これにより、Silero モデルの不調だけでなく、BLE マイク特有の低振幅な囁き声があっても Groq 送信を止めにくくしている。

## API 通信

### Groq Whisper（文字起こし）

```
POST https://api.groq.com/openai/v1/audio/transcriptions
Authorization: Bearer {groq_api_key}
Content-Type: multipart/form-data

file: audio.wav
model: whisper-large-v3-turbo
response_format: json
```

Whisper の JSON レスポンスから `text` に加えて `language` を受け取り、入力音声の言語推定に利用する。

### Groq Chat（AI 応答）

```
POST https://api.groq.com/openai/v1/chat/completions
Authorization: Bearer {groq_api_key}
Content-Type: application/json

{
  "model": "openai/gpt-oss-120b",
  "messages": [
    {
      "role": "system",
      "content": "Respond in the same language as the user's transcribed request. The detected input language is English (en). Do not translate unless the user explicitly asks for translation. Keep responses brief unless the user explicitly asks for a detailed explanation."
    },
    {
      "role": "user",
      "content": "{transcribed_text}"
    }
  ]
}
```

入力言語が判定できた場合のみ system prompt を追加し、Groq が音声入力と同じ言語で返答し、明示的に詳説を求められない限り短めに返答するよう制御する。  
入力言語が不明な場合は system prompt を付けず、従来に近い挙動を維持する。

## 応答言語と TTS

- `SpeechLanguageResolver.kt`
  - Whisper の `language` を優先し、必要なら転写テキストの文字種から言語コードを推定する
  - TTS 用の候補ロケール列を組み立てる

- `GroqChatRequestBuilder.kt`
  - 検出した言語コードをもとに Groq Chat 用の system prompt を生成する
  - 「同じ言語で返答し、明示的に要求されない限り翻訳しない。明示的に詳説を求められない限り短く答える」方針を Chat API に渡す

- `TtsTextFormatter.kt`
  - Markdown 記法や表の区切りを読み上げ向けテキストへ整形する
  - 長い返答を `TextToSpeech.getMaxSpeechInputLength()` 以下のチャンクへ分割する

- `VoiceViewModel.kt`
  - 候補ロケールを順番に試しながら TTS を実行する
  - 長文応答は複数 utterance に分けてキューイングし、最後のチャンク完了で `READY` に戻す

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
| `INTERNET` | all | Groq API 通信 |
| `FOREGROUND_SERVICE` | all | BleConnectionService |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | 34+ | ForegroundService タイプ指定 |
| `POST_NOTIFICATIONS` | 33+ | フォアグラウンドサービス通知 |

## GroqSettingsActivity

Groq API キーを `SharedPreferences("groq_prefs")` に保存する。  
キー名: `groq_api_key`

VoiceViewModel は毎回 API 呼び出し時に SharedPreferences から読み出す（キャッシュなし）。
