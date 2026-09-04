# Voice Harness

Android アプリ。XIAO nRF52840 Sense をウェアラブルマイクとして使い、オンデバイス / Groq / OpenRouter で音声認識・AI応答を行い、Android TTS（または Even Realities G2）で出力する。電源長押しのデジタルアシスタント（下部シート UI）にも対応する。

## 概要

```
[Harness Node]
  ・手首ジェスチャー → FW 自律 TX 0x01 / 0x02
  ・シングルタップ (0x14) → ホストが RX 0x01/0x00（パススルー中はページ送りのみ）
  ・ダブルタップ (0x12) → パススルー ON/OFF（処理中はパイプライン割り込み）
        │ BLE TX 0x01 (録音開始)
        ▼
[Android: PCM 蓄積]
  ・開始/終了キュー音（MEDIA 経路）
  ・他アプリ上の録音オーバーレイ（要 SYSTEM_ALERT_WINDOW）
  ・ROLE_ASSISTANT 時はヘッドレス Assist で画面テキスト/スクショ取得
        │ BLE TX 0x02（録音停止）
        ▼
[Silero VAD + FFT fallback]
        │ 音声あり
        ▼
[ASR: Gemma / Qwen3-ASR / Groq Whisper] → 文字起こし
        │
        ▼
[Chat: Gemma / LFM 2.5 / Groq / OpenRouter] → AI 応答
  （取得できた ScreenContext を添付。自アプリ/ロック/画面オフは除外）
        │
        ▼
[Android TTS or Even G2] → 出力

[読書パススルー]
  ・ホームのユーザー補助（Accessibility）必須（Kindle 自動／ページめくり）
  ・G2 プラグインが本文をページ分割、Node シングルタップで送り

[電源長押し ROLE_ASSISTANT]
        → 下部シート UI（自動録音なし）
        → テキスト or マイク送信
        → 任意で画面テキスト / スクリーンショットを添付
        → 同じ AssistantGateway → LLM
```

**FW は `0.0.94+` を前提**（single/double は notify-only）。アプリとセットで更新する。

**モデル設定** で ASR と LLM を独立に選択する（旧プロファイルは初回のみ双方へコピー）:

| 役割 | 選択肢 |
|---|---|
| 音声認識 (ASR) | Gemma 4 E2B / Qwen3-ASR / Groq Whisper |
| 応答モデル (LLM) | Gemma 4 E2B / LFM 2.5 / Groq Chat / **OpenRouter** |

OpenRouter は明示オプトイン（API キー + モデル選択が必須）。既定 ASR/LLM は変更しない。

電話マイクでの録音も可能（BLE 未接続時のフォールバック）。

---

## セットアップ

### 必要なもの

- Android 12 以上 (API 31+)
- XIAO nRF52840 Sense（`harness-node/nordic-main` ファームウェア書き込み済み）
- ローカル利用時: Qwen / Gemma / LFM のオンデバイスモデル（詳細は下記ドキュメント参照）
- クラウド利用時: Groq API キー、および/または OpenRouter API キー（モデルファイル不要）
- デジタルアシスタント利用時: 設定アプリで Voice Harness をデフォルトのデジタルアシスタントに指定
- （任意）Even Realities G2とEven Realities App、Even Hubプラグイン（AI返答をグラスへ表示する場合）

### アプリのインストール

```bash
git clone https://github.com/g150446/voice-harness-android.git
cd voice-harness-android
./scripts/prepare-qwen-asr-native.sh
./gradlew :app:installDebug
```

Tailscale 経由の例（razr 50s、事前に `adb tcpip 5555` 済み）:

```bash
adb connect 100.102.210.64:5555
./gradlew :app:installDebug
```

### 初期設定

1. アプリを起動し、要求された権限（Bluetooth・マイク・通知）を許可する
2. 画面下部の **モデル設定** で ASR と LLM をそれぞれ選ぶ
   - **ローカル**: `models/` を配置し `./scripts/push-all-models.sh`、または画面から取り込み → モデルを読み込む
   - **Cloud (Groq)**: API キーを入力して保存
   - **OpenRouter（LLM のみ）**: API キー保存 → モデル一覧更新 → モデルを明示選択
3. （推奨）デジタルアシスタント: システム設定で Voice Harness をアシスタントに設定
   - 電源長押しで下部シート
   - HarnessNode ジェスチャー時の **画面テキスト／スクショ自動添付** にも必要
4. （推奨）**録音中アイコンの表示を許可**（他のアプリの上に表示）
   - 他アプリ表示中に赤いマイクオーバーレイで録音中を示す

### nRF52840 との接続

ペアリング設定は不要。アプリ起動後に自動スキャンが始まり、対象デバイスを見つけると自動接続する。

- **BLE Scanning...** → スキャン中（青ドット）
- **BLE Connecting...** → 接続中（オレンジドット）
- **BLE Connected** → 接続完了（緑ドット）
- **BLE Off** → 未接続（グレードット）

30 秒スキャンしても見つからない場合は指数バックオフ（2 秒→4 秒→…最大 60 秒間隔）で自動リトライする。

---

## 使い方

### BLE 録音（nRF52840 ジェスチャー）

1. nRF52840 で録音ジェスチャーを行う
2. **開始キュー音**（上昇2音）が鳴り、PCM を蓄積
   - 他アプリ上では赤いマイク **オーバーレイ**（許可時）と通知「録音中…」
   - 自アプリ UI では **Recording (BLE)...**
3. 停止ジェスチャーで **終了キュー音**（下降2音）→ `Silero VAD` で音声判定し、必要に応じて FFT フォールバック / rescue → テキスト化・AI 応答・読み上げ
4. デフォルトアシスタント設定時、録音開始時点の **他アプリ画面**（Assist テキスト + スクショ）を LLM に添付
   - 自アプリ画面・ロック・画面オフでは添付しない
   - JPEG は OpenRouter の画像対応モデルのみ。他バックエンドは画面テキストのみ
5. 結果は **履歴** に保存される。ジェスチャ時は FW 診断（`0x30`）も同じ履歴に付く

AI が読み上げ中に再度ジェスチャーを行うと、読み上げを中断して新しい対話を開始できる。

キュー音は **メディア音量**（TTS と同じ経路）。マナーモードで通知音が消えていても聞こえる。

ライブの診断ストリームはホームの **ジェスチャ診断**、仕様は `documents/history_feature.md` / `documents/ble_protocol.md`。

### 電話マイク録音（手動）

1. **● Record (Mic)** ボタンをタップして録音開始
2. **■ Stop** をタップして録音停止 → 自動でテキスト化・AI 応答・読み上げ

読み上げ中は **■ Stop Speaking** で中断し、すぐに新しい録音を開始できる。

### AI返答の出力先

ホーム画面の **AI返答の出力先** で、従来のTTS音声とEven Realities G2を切り替えられる。
G2を選ぶとAI返答をEven Hubプラグイン経由でグラスへ表示し、電話画面と履歴にも返答を残す。
Even Hubプラグインが未接続の場合は、自動的にTTSへ戻して返答を読み上げる。

G2を利用する前にEven Realities Appでグラスをペアリングし、EvenHubからインストールした
**Voice Harness**プラグインを起動する。アプリ内の **Even Realities Appを開く** ボタンから
コンパニオンを起動できる。インストール後の通常利用にMacや開発サーバーは不要。

QR/URLと`npm run dev`はプラグイン開発時だけ使用する。配布パッケージの作成・非公開登録と
開発セッション終了時の注意事項は[`even-g2/app/README.md`](even-g2/app/README.md)を参照。

> Vuzix Z100向け実装（`SmartGlassesOutputManager`）は将来再配線用にコードとSDK依存を残しているが、
> 実行パスからは外している。詳細は `documents/smart_glasses_output.md`。

---

## ビルド・開発

```bash
# Qwen3-ASR用runtimeを準備（初回のみ）
./scripts/prepare-qwen-asr-native.sh

# テストとデバッグビルド
./gradlew testDebugUnitTest :app:assembleDebug

# デバッグビルド
./gradlew :app:assembleDebug

# デバイスへインストール
./gradlew :app:installDebug

# ログ確認（VAD・BLE・録音 UI / キュー音）
adb logcat -s VoiceProcessor SileroVad BleManager BleConnectionService \
  RecordingCuePlayer RecordingOverlay HeadlessScreenCapture HarnessVoiceSession
```

### ADB（USB / Tailscale）

```bash
# 初回または再起動後: USB を挿した状態で TCP 5555 を有効化し Tailscale 接続
./scripts/adb-tailscale.sh enable-usb

# USB を抜いたあと / 別 Mac から: Tailscale IP で再接続のみ
./scripts/adb-tailscale.sh connect

./scripts/adb-tailscale.sh status
./scripts/adb-tailscale.sh disconnect
```

- 既定ホスト名: `motorola-razr-50s`（`TS_HOST=...` で変更可）
- 既定ポート: `5555`
- 同一 LAN / テザリングのみなら従来どおり `./scripts/adb-wireless.sh`
- Tailscale 上で端末が online であること（`tailscale status`）


### VAD / ASR 幻覚対策メモ

- BLE 経路は `Silero VAD` を優先し、`maxProb` が異常に低い場合は FFT ベース解析に自動フォールバックする
- FFT 側は無音フレームを除いた「アクティブフレーム」基準で判定する
- Silero が stuck のときだけ、小声の実測振幅以上ならエネルギー救済する。無音ノイズは拒否する
- 録音停止はファームウェアの停止ジェスチャ（TX `0x02`）のみ。Android は無音で RX `0x00` を送らない
- `ちいかわ` 系語彙は 1 パス目に載せない。転写に `アニメ` があるときだけ 2 パス目で Preferred spellings を付与する
- `AsrTextFilter` が語彙エコー（アニメ無しの3語列挙）と儀礼句だけの幻覚（`はい、ありがとうございます` など）を破棄する

### 主要ファイル

| ファイル | 役割 |
|---|---|
| `BleManager.kt` | BLE スキャン・接続・パケット解析 |
| `BleConnectionService.kt` | BLE をフォアグラウンドサービスとして管理 |
| `VoiceProcessor.kt` | 録音制御・停止後 VAD・AI・TTS |
| `SilenceEndpointTracker.kt` | （未使用）連続無音カウンタ。停止は FW ジェスチャのみ |
| `AsrVocabulary.kt` | ASR Preferred spellings とトリガー語（アニメ） |
| `AsrTextFilter.kt` | 語彙エコー・儀礼句など ASR 幻覚の破棄 |
| `ModelManager.kt` | モデル探索、取り込み、ASR/LLM 独立設定 |
| `OnDeviceAiFacade.kt` | ASR/LLM 独立ルーティング + 共有 BackendRegistry |
| `QwenOnDeviceBackend.kt` | Qwen3-ASR + LFM 2.5（LEAP） |
| `GemmaOnDeviceBackend.kt` | Gemma 4 の実行 |
| `GroqVoiceAiBackend.kt` | Groq Whisper + Chat Completions |
| `OpenRouterLlmBackend.kt` | OpenRouter Chat Completions（LLM のみ） |
| `GroqPrefs.kt` / `OpenRouterPrefs.kt` | API キー保存（OpenRouter は Keystore 暗号化） |
| `assistant/*` | デジタルアシスタント Session / Activity / 画面コンテキスト |
| `assistant/HeadlessScreenCapture.kt` | HarnessNode 用ヘッドレス Assist + スクショ取得 |
| `RecordingOverlayController.kt` | 他アプリ上の録音中オーバーレイ |
| `RecordingCuePlayer.kt` | 録音開始/終了キュー音（USAGE_MEDIA） |
| `BleSpeechDetector.kt` | BLE PCM の DC 除去、FFT フォールバック、スペクトル解析 |
| `EvenG2ReadingSession.kt` / `EvenG2BridgeServer.kt` | Even G2 表示セッションと loopback ブリッジ |
| `SmartGlassesOutputManager.kt` | Vuzix Z100 実装（実行未使用・将来再配線用アーカイブ） |
| `MainActivity.kt` | UI（Jetpack Compose） |
| `GroqSettingsActivity.kt` | モデル設定画面（ASR/LLM 独立 + OpenRouter） |

---

## 詳細ドキュメント

- [`documents/ble_protocol.md`](documents/ble_protocol.md) — BLE パケット仕様（ジェスチャ診断 `0x30` 含む）
- [`documents/history_feature.md`](documents/history_feature.md) — 会話履歴とジェスチャ判定の保存・UI
- [`documents/ble_audio_reliability.md`](documents/ble_audio_reliability.md) — Bluetoothヘッドセット併用時の音声経路、PCM送達保証、障害調査
- [`documents/smart_glasses_output.md`](documents/smart_glasses_output.md) — Even G2 出力（現行）と Vuzix Z100 アーカイブ仕様
- [`even-g2/app/README.md`](even-g2/app/README.md) — Even Hub プラグイン（Voice Harness G2）
- [`documents/even_g2_macless_deployment.md`](documents/even_g2_macless_deployment.md) — Macなし運用、非公開Beta配布、期限切れ表示の復旧
- [`documents/vad.md`](documents/vad.md) — Silero VAD / FFT フォールバックの仕様とチューニング
- [`documents/architecture.md`](documents/architecture.md) — アーキテクチャ詳細
- [`documents/ondevice_ai.md`](documents/ondevice_ai.md) — AIバックエンド（ローカル / Groq / OpenRouter）の準備・運用
- [`documents/groq_cloud.md`](documents/groq_cloud.md) — Cloud (Groq)
- [`documents/openrouter.md`](documents/openrouter.md) — OpenRouter LLM
- [`documents/opendroid-integration.md`](documents/opendroid-integration.md) — デジタルアシスタント統合
- [`documents/voice-harness-android-openrouter-plan.md`](documents/voice-harness-android-openrouter-plan.md) — OpenRouter・画面コンテキスト・対話 UI 実装計画（正本）
- [`documents/ondevice_gemma.md`](documents/ondevice_gemma.md) — Gemma 4 統合メモ
- [`documents/qwen_asr_encoder_issue.md`](documents/qwen_asr_encoder_issue.md) — Qwen3-ASR方式の調査・端末検証結果
