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
│ ・自動再接続        │  │ ・RecordingCue 音  │
│ ・優先デバイス保存   │  │ ・Silero VAD / FFT  │
└─────────────────────┘  │ ・AI (local/Groq)  │
                         │ ・Android TTS      │
                         │ ・ヘッドレス画面取得│
                         └────────────────────┘

BleConnectionService 付帯:
  RecordingOverlayController … RECORDING 中の他アプリ上インジケータ
  HeadlessScreenCapture ……… ROLE_ASSISTANT 時の Assist/スクショ

BleManager ── Channel<BleVoiceInput> ──▶ BleConnectionService ──▶ VoiceProcessor
             （PCMとイベントを同じ順序で配送）

VoiceProcessor ── ResponseOutputTarget ──┬──▶ Android TTS
                                        └──▶ EvenG2ReadingSession ──▶ Even Hub plugin ──▶ G2

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
| `RECORDING` | 録音中（BLE）。開始キュー音・オーバーレイ・通知「録音中…」 |
| `TRANSCRIBING` | 選択中モデルで文字起こし中 |
| `RESPONDING` | 選択中モデルで応答生成中 |
| `SPEAKING` | TTS 読み上げ中 |
| `ERROR` | エラー発生 |

### 録音フィードバック

| 要素 | 実装 | 備考 |
|---|---|---|
| 開始/終了音 | `RecordingCuePlayer` | `USAGE_MEDIA`（TTS と同じ）。ホーム「録音キュー音」で ON/OFF（**既定オフ**） |
| ダブルタップ中断 | `shouldInterruptOnDoubleTap` | RECORDING / TRANSCRIBING / RESPONDING / SPEAKING をキャンセル。直後 2s は single 録音コマンド抑制＋遅延 start Job cancel |
| 画面オーバーレイ | `RecordingOverlayController` | `SYSTEM_ALERT_WINDOW`。未許可時は通知文言のみ |
| 画面コンテキスト | `HeadlessScreenCapture` | 録音開始時。自アプリ/ロック/画面オフは破棄 |

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
val doubleTapStatus: StateFlow<DoubleTapStatus>

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
 音声処理パイプライン（完全性検査 → 停止後 VAD → AI backend → TTS）を実行し、結果を
companion objectの状態Flowへ書き込む。ViewModel / UIはその状態だけを観察する。
AI backend は ASR（`SttBackendId`）と LLM（`LlmBackendId`）を独立選択する。
同一ローカルモデルは `BackendRegistry` で共有し二重ロードしない。OpenRouter は LLM のみ。

### デジタルアシスタント

```
ROLE_ASSISTANT
  → HarnessVoiceInteractionSession（setUiEnabled(false)、自動 ASR なし）
  → HarnessAssistantActivity（透明全画面 + 下部シート）
  → AssistantSessionController（会話 ID・画面コンテキスト・UI StateFlow）
  → BleConnectionService.submitAssistantRequest
  → VoiceProcessor / BackendAssistantGateway
  → LlmBackend.chat(ChatRequest + optional ScreenContext)
```

- 画面テキスト/JPEG は呼び出し中だけ保持。履歴には入れない。
- HarnessNode 経路は画面情報なしのまま。
- 詳細は [`opendroid-integration.md`](opendroid-integration.md)。

シングルタップ `0x14` / ダブルタップ `0x12` は同じ入力 Channel で受信し、Service が
回数を UI と G2 ブリッジへ公開する。FW `0.0.94+` ではどちらも notify-only。
single はホスト承認録音または G2 ページ送り、double はリーダーモード トグル／パイプライン割り込み。

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

## BLE 録音フロー（TX イベント主導・開始経路は複数）

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
    │                               │
    │── 0x02 (RecordingStopped) ───▶│
    │                               │ handleBleRecordingStopped(...)
    │                               │   PCM completeness check
    │                               │   Silero VAD
    │                               │   FFT fallback / stuck 時のみ energy rescue
    │                               │   buildWavFile()
    │                               │   ASR → Chat → TTS or Even G2
```

アプリ状態は常に TX `0x01`/`0x02` に追従する。開始のきっかけは次のいずれか:

| 経路 | FW `0.0.95+` | Android |
|---|---|---|
| シングルタップ (`0x14`) | **notify-only**（**既定の録音操作**） | リーダーモード OFF 時に RX `0x01`/`0x00` でホスト承認 |
| 手首ジェスチャー | 検出スイッチ ON 時のみ自律 `0x01`/`0x02`（**既定 OFF**） | ホーム「ジェスチャー録音」→ RX `0x07` |
| リーダーモード ON の single | notify-only | RX なし。G2 `singleTapCount` でページ送り |
| ダブルタップ (`0x12`) | notify-only | リーダーモード ON/OFF（ON は G2 接続時のみ。処理中は割り込み） |

無音による RX `0x00` 自動停止は廃止済み。  
詳細は [`ble_protocol.md`](ble_protocol.md) / [`gesture_detect_default_off.md`](gesture_detect_default_off.md) /
[`smart_glasses_output.md`](smart_glasses_output.md)。

1秒以上の録音では、16 kHz / 16-bit / monoから算出したPCM時間が壁時計の録音時間の
70%未満ならASRへ進めない。これは欠落音声による無関係な文字列生成を防ぐ境界であり、
VADより前に評価する。Bluetoothヘッドセットとの併用を含む詳細は
[`ble_audio_reliability.md`](ble_audio_reliability.md)を参照。

## BLE 音声判定

BLE 音声は `VoiceProcessor` が担当し、次の順で判定する。

1. 録音中: PCM を蓄積するのみ（ホスト無音自動停止なし）
2. 録音後: `hasSpeechInPcm()` がクリップ全体を `SileroVad.kt` で 512 サンプルごとに推論する
3. Silero が異常に低い確率へ張り付く場合だけでなく、通常推論でも音声比率が閾値未満だった場合は `BleSpeechDetector.kt` の FFT 判定で再評価する
4. Silero が stuck のときだけ、`peakAfterDC` / `rmsAfterDC` が小声の実測値以上ならエネルギー救済する。通常の非音声判定を振幅だけで上書きしない

無音ノイズを ASR に渡すとプロンプト語彙を幻覚するため、救済は小声の実測値に限る。詳細は [`vad.md`](vad.md) を参照。

## ASR 語彙と幻覚フィルタ

1. `OnDeviceAiFacade.transcribe()` はまずトリガー無し語彙だけで 1 パス目を実行する
2. 転写に `アニメ` / `あにめ` / `anime` があり、まだ `ちいかわ` 等の正しい表記が無いときだけ、語彙付きで 2 パス目を実行する
3. `AsrTextFilter` が次を破棄する（無音/雑音扱い）:
   - アニメ無しで `ちいかわ、ハチワレ、うさぎ` だけ並んだ語彙エコー
   - `はい、ありがとうございます` のような儀礼句だけの幻覚（単独の「はい」「うん」「ありがとう」は残す）

語彙の追加方法は [`ondevice_gemma.md`](ondevice_gemma.md) の「ASR 認識語彙」を参照。

## 誤発火の発話ゲート

`AsrTextFilter` を通過したあと、`UtteranceIntentGate` が「アシスタントへの依頼か」を
判定し、そうでなければ LLM 呼び出しと TTS 発話に到達させずに履歴へ落とす。録音開始
ジェスチャーは日常の腕の動きと motion 特徴量で分離できず、FW 側の閾値では止められない
（データによる棄却の詳細は [`gesture_false_trigger.md`](gesture_false_trigger.md)）。

抑制は**非依頼の積極的証拠があるときだけ**行い、証拠がなければ通す。依頼形・疑問形・
アプリコマンド語は無条件で通過する。判定点は
`VoiceProcessor.transcribeAndRespondOnDevice()` で、ジェスチャー録音経路のみに効く。

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
保存する（UI上の `SMART_GLASSES` は Even G2）。電話画面の返答StateFlowと履歴保存は
出力先に関係なく更新する。

G2選択時は `EvenG2ReadingSession` が loopback ブリッジ（`EvenG2BridgeServer`）経由で
Even Hub プラグインへ本文を載せる。プラグインが直近にポーリングしていれば表示成功とみなし
TTSを抑止する。未接続なら同じ返答をAndroid TTSへフォールバックする。新しいBLE録音開始時や
出力先を音声へ戻したときは表示セッションをクリアする。

Vuzix Z100 向けの `SmartGlassesOutputManager` は実行パスから外し、将来再配線用に残置している。
詳細は [`smart_glasses_output.md`](smart_glasses_output.md) を参照。

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
