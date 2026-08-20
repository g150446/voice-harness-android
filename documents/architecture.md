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
│  ・UI 用 StateFlow の収集のみ                │
│  ・スキャン結果 / 選択中デバイス管理          │
│  ・BleConnectionService companion への委譲   │
└──────────────────────┬──────────────────────┘
                       │ StateFlow (companion object)
┌──────────────────────▼──────────────────────┐
│           BleConnectionService               │
│  (ForegroundService / START_STICKY)          │
│  ・BleManager のライフサイクル管理            │
│  ・VoiceProcessor のライフサイクル管理        │
│  ・PARTIAL_WAKE_LOCK (CONNECTED 時保持)       │
│  ・BLE / 音声状態を companion object で公開  │
└──────────┬──────────────────────────────────┘
           │ serviceScope                      │
┌──────────▼──────────┐  ┌────────────────────┐
│     BleManager      │  │   VoiceProcessor   │
│ ・BLE スキャン・接続 │  │ ・BLE イベント収集  │
│ ・GATT / パケット解析│  │ ・PCM バッファ管理  │
│ ・自動再接続        │  │ ・ストリーミング VAD │
│ ・優先デバイス保存   │  │ ・Silero VAD / FFT  │
└─────────────────────┘  │ ・On-device AI      │
                         │ ・Android TTS      │
                         └────────────────────┘

BleManager ── Channel<BleVoiceInput> ──▶ BleConnectionService ──▶ VoiceProcessor
             （PCMとイベントを同じ順序で配送）

VoiceProcessor ── ResponseOutputTarget ──┬──▶ Android TTS
                                        └──▶ SmartGlassesOutputManager ──▶ Z100

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
| `TRANSCRIBING` | 選択中モデルで文字起こし中 |
| `RESPONDING` | 選択中モデルで応答生成中 |
| `SPEAKING` | TTS 読み上げ中 |
| `ERROR` | エラー発生 |

### BleConnectionState

`DISCONNECTED` → `SCANNING` → `CONNECTING` → `CONNECTED`

通常は `CONNECTED` から切断されたときに保存済みデバイスへ自動再接続を試みる。  
ただしユーザーが `Disconnect` を押した場合は `DISCONNECTED` のまま待機し、`Scan devices` から明示的に再接続するまで自動再接続しない。

## 状態Flowと音声入力Channel

UIへ公開する状態と、高レートのBLE音声入力は別の仕組みで扱う。
ViewModelはServiceより先に生成されることがあるため、UI状態の `StateFlow` を
`BleConnectionService` の **companion object** に置き、appライフタイムで保持する。

```kotlin
// BleConnectionService companion object (app ライフタイム)
// BLE 状態
val connectionState: StateFlow<BleConnectionState>
val scannedDevices: StateFlow<List<BleDeviceInfo>>
val preferredDevice: StateFlow<BleDeviceInfo?>
val batteryLevel: StateFlow<Int?>
val isPrimary: StateFlow<Boolean>

// 音声処理状態 (VoiceProcessor が書き込む)
val voiceState: StateFlow<VoiceState>
val transcription: StateFlow<String>
val response: StateFlow<String>
val errorMessage: StateFlow<String>
val bleMode: StateFlow<Boolean>
```

PCMと録音イベントには別々の `SharedFlow` を使わない。`BleManager` は
`BleVoiceInput.Audio` と `BleVoiceInput.Event` を単一の `Channel.UNLIMITED` に投入し、
Serviceの1つのcollectorが `VoiceProcessor.handleBleInput()` へ渡す。これにより、
バッファ満杯による `tryEmit()` の無言の失敗と、録音停止イベントによるPCMの追い越しを
防ぐ。

Serviceは `VoiceProcessor` を先に生成してから `BleManager` を開始する。VoiceProcessorは
音声処理パイプライン（ストリーミング無音監視 → 完全性検査 → VAD → オンデバイスAI → TTS）を実行し、結果を
companion objectの状態Flowへ書き込む。ViewModel / UIはその状態だけを観察する。

### バックグラウンド動作の仕組み

Activity が破棄された（画面消灯・タスクスワイプ・再起動）後も処理が継続できる理由：

| 仕組み | 内容 |
|---|---|
| `ForegroundService` | 通知付きサービスは OS に強制終了されにくい |
| `START_STICKY` | サービスが強制終了されても OS が自動再起動する |
| `android:stopWithTask="false"` | タスクスワイプでもサービスが停止しない |
| `PARTIAL_WAKE_LOCK` | BLE 接続中は CPU をスリープさせず GATT コールバックを確実に受ける |
| バッテリー最適化除外 | OEM の省電力機能によるサービス強制停止を防ぐ（初回起動時にシステムダイアログで要求） |
| `BootReceiver` | 再起動後に `BleConnectionService` を自動起動する |
| `serviceScope` で処理 | `viewModelScope` と異なり Activity 破棄の影響を受けない |

## BLE 録音フロー（ファームウェア主導）

```
nRF52840                        Android
    │── 0x01 (RecordingStarted) ──▶│
    │                               │ handleBleRecordingStarted()
    │                               │   requestConnectionPriority(HIGH)
    │                               │   isCollectingPcm = true
    │                               │   state = RECORDING
    │                               │   cue → built-in speaker
    │── [audio packets] ────────────▶│
    │                               │   pcmBuffer.write(packet.pcmData)
    │                               │   ストリーミング Silero（無音 5 秒監視）
    │                               │
    │◀─ RX 0x00 (無音 5 秒時のみ) ──│
    │── 0x02 (RecordingStopped) ───▶│
    │                               │ handleBleRecordingStopped()
    │                               │   PCM completeness check
    │                               │   Silero VAD
    │                               │   FFT fallback / stuck 時のみ energy rescue
    │                               │   buildWavFile()
    │                               │   Qwen3-ASR / Gemma ASR
    │                               │   Qwen 3.5 / Gemma Chat
    │                               │   TTS 読み上げ
```

録音開始はファームウェアのジェスチャー（TX `0x01`）が担う。停止はジェスチャー（TX `0x02`）に加え、Android が連続無音 5 秒を検出したとき RX `0x00` を送る。  
Android アプリ側の UI は BLE デバイスのスキャン・選択・接続・切断だけを担当する。  
スキャン結果はアプリ内で単一選択リストとして表示し、ユーザーは対象デバイスを選んで `Connect` する。

1秒以上の録音では、16 kHz / 16-bit / monoから算出したPCM時間が壁時計の録音時間の
70%未満ならASRへ進めない。これは欠落音声による無関係な文字列生成を防ぐ境界であり、
VADより前に評価する。Bluetoothヘッドセットとの併用を含む詳細は
[`ble_audio_reliability.md`](ble_audio_reliability.md)を参照。

## BLE 音声判定

BLE 音声は `VoiceProcessor` が担当し、次の順で判定する。

1. 録音中: `SilenceEndpointTracker` が 512 サンプルごとに Silero 確率を見て、連続無音 5 秒で RX `0x00` を送る
2. 録音後: `hasSpeechInPcm()` がクリップ全体を `SileroVad.kt` で 512 サンプルごとに推論する
3. Silero が異常に低い確率へ張り付く場合だけでなく、通常推論でも音声比率が閾値未満だった場合は `BleSpeechDetector.kt` の FFT 判定で再評価する
4. Silero が stuck のときだけ、`peakAfterDC` / `rmsAfterDC` が小声の実測値以上ならエネルギー救済する。通常の非音声判定を振幅だけで上書きしない

無音ノイズを ASR に渡すとプロンプト語彙を幻覚するため、救済は小声の実測値に限る。詳細は [`vad.md`](vad.md) を参照。

## オンデバイスAI

`OnDeviceAiFacade`が選択中の`VoiceAiBackend`へ処理を委譲する。デフォルトのQwenは
Qwen3-ASR GGUFで文字起こしし、Qwen 3.5 LiteRT-LMで応答を生成する。Gemmaプロファイルは
Gemma 4 LiteRT-LMで両方を処理する。モデル探索と状態管理は`ModelManager`が担当する。

詳細は[`ondevice_ai.md`](ondevice_ai.md)を参照。

## 応答言語と TTS

- `SpeechLanguageResolver.kt`
  - ASRの言語コードを優先し、必要なら転写テキストの文字種から言語コードを推定する
  - TTS 用の候補ロケール列を組み立てる

- `LitertLlmSupport.kt`
  - 検出した言語コードをもとにオンデバイスChat用のsystem promptを生成する
  - 同じ言語で簡潔に返答する方針とreminder toolをモデルへ渡す

- `TtsTextFormatter.kt`
  - Markdown 記法や表の区切りを読み上げ向けテキストへ整形する
  - 長い返答を `TextToSpeech.getMaxSpeechInputLength()` 以下のチャンクへ分割する

- `VoiceProcessor.kt`
  - 候補ロケールを順番に試しながら TTS を実行する
  - 長文応答は複数 utterance に分けてキューイングし、最後のチャンク完了で `READY` に戻す

## AI返答の出力先

`ResponseOutputTarget` は `PHONE_AUDIO` と `SMART_GLASSES` を持ち、SharedPreferencesへ
保存する。電話画面の返答StateFlowと履歴保存は出力先に関係なく更新する。

Z100選択時は `SmartGlassesOutputManager` がVuzix Connect経由のSDK状態を監視し、返答が
完成した時点でのみグラスの制御を要求する。`requestControl()`後は非同期の
`controlledByMe`成功通知をタイムアウト付きで待つ。制御取得後は
`Layout.TEXT_BOTTOM_LEFT_ALIGN` と `sendText()` で全文を一度に表示する。表示成功から
12秒後に自動で制御を解放し、次のHarnessNode録音とZ100 BLEトランザクションが重ならない
ようにする。新しいBLE録音開始時は、表示中なら消去して制御解放し、既にidleなら
Z100向け操作を省略する。

SDK利用不可、未リンク、未接続、制御取得失敗、表示開始例外の場合は、同じ返答をAndroid
TTSへフォールバックする。Z100表示に成功した場合はTTSを実行せず、音声状態を `READY`
へ戻して次の録音を受け付ける。詳細は
[`smart_glasses_output.md`](smart_glasses_output.md)を参照。

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
| `INTERNET` | all | モデル準備等の既存ネットワーク機能 |
| `FOREGROUND_SERVICE` | all | BleConnectionService |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | 34+ | ForegroundService タイプ指定 |
| `POST_NOTIFICATIONS` | 33+ | フォアグラウンドサービス通知 |
| `WAKE_LOCK` | all | BLE 接続中の CPU スリープ防止 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 23+ | OEM 省電力によるサービス停止防止 |

## GroqSettingsActivity

既存Activity名は維持しているが、画面の役割はオンデバイスモデル設定である。Qwen/Gemmaの
プロファイル選択、モデルファイル取り込み、検出状態と推論時間の表示を行う。
