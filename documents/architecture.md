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
  HeadlessScreenCapture ……… ROLE_ASSISTANT 時の Assist/スクショ（不適格の理由をログへ出す）
  HarborMirrorController …… 複数Macの暗号化資格情報、Harbor API/UI状態、G2ミラーを一元管理
                             確定済みコマンドは planHarborSubmit で呼び出し列に落としてから順に実行する
  EpubReaderHub/Controller … EPUBモード中、開いている本の位置を保持し、本文チャンクと目次をG2へ出す（phone画面・音声・G2のadvance要求から操作）
  OpenClawMirrorController …… OpenClawモード中、セッション履歴（アプリのチャット画面と同じ）をG2へミラー（読み取り専用）

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
| ダブルタップ中断 | `shouldInterruptOnDoubleTap` | 通常の RECORDING / TRANSCRIBING / RESPONDING / SPEAKING をキャンセル。モード指示録音は確定。直後 2s は single 録音コマンド抑制＋遅延 start Job cancel |
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
OpenClaw は LLM ではなく応答先で、操作モードが OpenClaw のときだけ `OnDeviceAiFacade`
（`isOpenClawRoute()`）が選択中の LLM の代わりに Mac の Gateway `/v1/chat/completions` へ送る。
Harbor モードは別入口の `OpenClawHarborInterpreter` から、ワークスペース単位のセッションで
同じ Gateway に解釈だけを依頼する。**解釈は共有・実行経路は分離**で、Harbor の
`/v1/workspaces/{id}/instruction` と `/key` を叩くのは常にアプリ側（タップ確認の後）。
Harness NodeのBLE音声とアプリ内Assistantの音声／テキストは、入口は異なっても
`BackendAssistantGateway` とOpenClawの安定セッションを共有する。応答はG2接続中は
`EvenG2ReadingSession`を優先し、未接続時は音声入力を電話TTS、テキスト入力を
Assistant UIへ届ける。Node音声だけは Harbor と同じく確認画面を経てから送る（`openclaw.md`）。

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
ホームの設定で single / double のどちらをホスト承認録音に使うか選べる（既定 single）。
ただし**G2接続中はこの設定に関係なく single が録音を開始/終了することは一切なく**、
録音の開始/終了は常に double だけが担う。リーダー / Harbor要約・質問表示中の single は
G2 ページ送り、Harbor 確認待ちの single は実行。それ以外（Harbor 待機中を含む）の single は
何もしない。G2 接続中の double はモード別指示録音で、未接続時の double はホーム設定に従う。
通常処理中の double はパイプライン割り込み。
Harbor 確認画面では single = 実行、double = 取り消し（表示している
「シングルタップで実行 / ダブルタップで取り消す」に合わせる）。確認待ち
（`needs_clarification`）の画面だけは「ダブルタップで言い直す」で録音し直しになる。
意図解釈が終わると `VoiceProcessor` はAIの確認コメントだけをAndroid TTSで読み上げる。
画面上のタップ操作案内は読み上げず、TTS中の `SPEAKING` 状態でもpending確認をsingle/doubleへ
優先ルーティングする。確定または取消時はTTSを停止してから処理を続ける。
HarborモードはG2の接続状態から独立しており、Android画面とHarnessNodeだけでも動作する。
Androidのworkspace詳細画面とG2は同じアクティブMac/workspaceを参照し、G2切断時はミラーだけを停止する。
workspace 詳細画面は **端末 / プラン / 会話** を切り替える。プランは
`GET /v1/workspaces/{id}/plan`、会話は `GET /v1/workspaces/{id}/transcript` から取得する。
どちらも画面由来ではないので、スクロールで流れた部分も読める（取得できない場合は理由を出し、
端末出力で代用しない）。会話はページングで、`さらに遡る` が `next_before` カーソルで古い分を
前に継ぎ足す（`mergeHarborTranscript`）。**AI エージェントの pane は端末のスクロールバックに
履歴を残さない**（実測で約1画面分）ため、返答から指示まで遡る用途は端末表示では成立せず、
会話表示がその役割を担う。端末幅の横罫線は表示幅を実測して縮め（`HarborTextLayout`）、
キーのボタン群は既定で畳んでおく。端末表示の自動追従は、ユーザーが最下部にいるときだけ働く。
確認プロンプト表示中は、ホームと workspace 詳細画面に実行／取り消す／言い直すボタンも出る。
その手前の**解釈待ち**（STT確定〜確認コメント到着）は `BleConnectionService.harborInterpreting`
で画面に伝える。経路は2つあり、G2接続中の指示録音は `presentHarborConfirmSuspend`
（`VoiceProcessor.harborConfirmInterpreting` のsetterが写す）、G2未接続時は通常のAI対話
パイプラインが `harbor_command` tool で解釈するので `assistantGateway.submit` の前後
（try/finally）で立てて下ろす。どちらも
`HarborInterpretingCard`（「AIが指示を確認中…」＋取り消す）を出す。`VoiceState` は
`TRANSCRIBING` のままなので、状態列挙を増やさず別フラグにしている。待機中に電話の
`response` へ「解析中…」を流すことはしない（実行ボタン付きの確認カードに見えてしまうため）。
ボタンは `confirmHarborCommand`/`cancelHarborCommand` 経由で tap と同じ
`handleSingleTap`/`handleDoubleTap` を呼ぶだけなので、G2 やHarnessNodeが無くても確定できる。

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

### 優先接続（Android / Mac Handy）

両方同時接続すると、PCM と TX `0x01`/`0x02` は Node の primary 1本にしか届かない。
タップは全接続へ飛ぶ。ホーム「優先接続」が Android のとき、`BleManager` は
Handy の接続 800 ms 後 claim より後（0 / 1000 / 1600 ms）に RX `0x02` を再送し、
ホスト承認の録音開始前にも `0x02` を書く。Mac Handy 優先なら `0x31` で `0x03` yield。
詳細は [`ble_protocol.md`](ble_protocol.md) の「デュアル接続と優先接続」。

アプリ状態は常に TX `0x01`/`0x02` に追従する。開始のきっかけは次のいずれか:

| 経路 | FW `0.0.95+` | Android |
|---|---|---|
| シングルタップ (`0x14`) | **notify-only**（**既定の録音操作**） | **G2 未接続時のみ**、ホームで single 選択かつ AI 対話モード時に RX `0x01`/`0x00` でホスト承認。G2 接続中は録音の開始/終了を一切行わない |
| 手首ジェスチャー | 検出スイッチ ON 時のみ自律 `0x01`/`0x02`（**既定 OFF**） | ホーム「ジェスチャー録音」→ RX `0x07` |
| リーダーの single | notify-only | RX なし。G2 `singleTapCount` でページ送り |
| Harbor の single | notify-only | 確認待ち = 実行。それ以外（要約・質問の表示中／待機中）は録音を一切開始せず、要約・質問のページ送りはG2側（`singleTapCount`）に委ねる |
| Harbor の double | notify-only | 確認待ち = 取り消し。言い直し待ちのみ録音し直し |
| ダブルタップ (`0x12`) | notify-only | G2 接続中はモード別指示録音（先頭「グラスモード変更」だけモード切替）。録音の開始/終了は接続中は常にダブルタップのみが担う。未接続時はホームの double 選択時だけ録音 start/stop |
| M5 StickC single | RX `0x08 0x01` で notify-only（既定はローカルトグル） | 接続時に `0x08 0x01` を送り、上記のホスト承認へ統一 |

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
  - Harbor確認ではAIの確認コメントだけを読み、画面上の操作案内は音声へ混ぜない
  - Harbor完了候補を同じworkspace別OpenClawセッションで確認し、最大2文・160字の報告を読み上げる
  - TTS競合時は生成済み報告だけを保留し、OpenClawを再呼び出ししない

- `HarborMirrorController`
  - 確認済み音声指示の実行直前に対象workspaceと画面fingerprintを記録し、変化後の2秒安定を検出する
  - `not_waiting` は同一画面を最大3回、一時障害は30秒間隔でタイムアウトまで再確認する
  - 15分のタイムアウト、モード離脱、次の音声指示、報告生成のいずれかで追跡を終了する
  - `workspace.agent` が空なら `process` を使い、G2未接続でも最新transcriptと画面を候補として渡す
  - G2表示には従来のTerminal Harbor `g2-view`を使い、OpenClaw音声報告とは分離する

- `OpenClawHarborInterpreter.kt`
  - 音声解釈と完了確認を同じagent-scoped workspaceセッションへ直列化する
  - 完了確認にはtoolを公開せず、`not_waiting / completed / question / permission / choice`を返させる
  - OpenClaw障害時は同じプロンプトをGroq/OpenRouterで確認し、それも使えない場合だけ
    Terminal Harborの既存要約へフォールバックする

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
