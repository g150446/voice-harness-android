# Voice Harness

Android アプリ。XIAO nRF52840 Sense をウェアラブルマイクとして使い、オンデバイス / Groq / OpenRouter で音声認識・AI応答を行い、Android TTS（または Vuzix Z100）で出力する。電源長押しのデジタルアシスタント（下部シート UI）にも対応する。

## 概要

```
[nRF52840 ジェスチャー検知]
        │ BLE 0x01 (録音開始通知)
        ▼
[Android: PCM 蓄積]
        │ BLE TX 0x02（FW 停止ジェスチャ）
        ▼
[Silero VAD + FFT fallback]
        │ 音声あり
        ▼
[ASR: Gemma / Qwen3-ASR / Groq Whisper] → 文字起こし
        │
        ▼
[Chat: Gemma / LFM 2.5 / Groq / OpenRouter] → AI 応答
        │
        ▼
[Android TTS or Z100] → 出力

[電源長押し ROLE_ASSISTANT]
        → 下部シート UI（自動録音なし）
        → テキスト or マイク送信
        → 任意で画面テキスト / スクリーンショットを添付
        → 同じ AssistantGateway → LLM
```

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
- （任意）Vuzix Z100とVuzix Connect（AI返答をスマートグラスへ表示する場合）

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
3. （任意）デジタルアシスタント: システム設定で Voice Harness をアシスタントに設定し、電源長押しで下部シートを開く

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
2. 画面が **Recording (BLE)...** になり、PCM 音声を蓄積
3. 再度ジェスチャーを行うか、無音が 5 秒続くと録音停止 → `Silero VAD` で音声判定し、必要に応じて FFT フォールバック / rescue 判定を行ってからテキスト化・AI 応答・読み上げ
4. 結果は **履歴** に保存される。ジェスチャ時は FW 診断（`0x30` の実測値）も同じ履歴に付き、詳細で文字起こしと並べて確認できる

AI が読み上げ中に再度ジェスチャーを行うと、読み上げを中断して新しい対話を開始できる。

ライブの診断ストリームはホームの **ジェスチャ診断**、仕様は `documents/history_feature.md` / `documents/ble_protocol.md`。

### 電話マイク録音（手動）

1. **● Record (Mic)** ボタンをタップして録音開始
2. **■ Stop** をタップして録音停止 → 自動でテキスト化・AI 応答・読み上げ

読み上げ中は **■ Stop Speaking** で中断し、すぐに新しい録音を開始できる。

### AI返答の出力先

ホーム画面の **AI返答の出力先** で、従来のTTS音声とVuzix Z100を切り替えられる。
Z100を選ぶとAI返答全文をグラスへ一度に表示し、電話画面と履歴にも返答を残す。
Vuzix Connect、Z100のリンクまたは接続、グラスの制御取得に失敗した場合は、自動的に
TTSへ戻して返答を読み上げる。

Z100を利用する前にVuzix Connectでグラスをリンク・接続する。アプリ内の
**Vuzix Connectを開く** ボタンから設定画面を起動できる。

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

# ログ確認（VAD・BLE）
adb logcat -s VoiceProcessor SileroVad BleManager BleConnectionService
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
| `BleSpeechDetector.kt` | BLE PCM の DC 除去、FFT フォールバック、スペクトル解析 |
| `SmartGlassesOutputManager.kt` | Vuzix Z100の状態監視、制御取得、返答全文表示 |
| `MainActivity.kt` | UI（Jetpack Compose） |
| `GroqSettingsActivity.kt` | モデル設定画面（ASR/LLM 独立 + OpenRouter） |

---

## 詳細ドキュメント

- [`documents/ble_protocol.md`](documents/ble_protocol.md) — BLE パケット仕様（ジェスチャ診断 `0x30` 含む）
- [`documents/history_feature.md`](documents/history_feature.md) — 会話履歴とジェスチャ判定の保存・UI
- [`documents/ble_audio_reliability.md`](documents/ble_audio_reliability.md) — Bluetoothヘッドセット併用時の音声経路、PCM送達保証、障害調査
- [`documents/smart_glasses_output.md`](documents/smart_glasses_output.md) — Vuzix Z100へのAI返答出力とフォールバック仕様
- [`documents/vad.md`](documents/vad.md) — Silero VAD / FFT フォールバックの仕様とチューニング
- [`documents/architecture.md`](documents/architecture.md) — アーキテクチャ詳細
- [`documents/ondevice_ai.md`](documents/ondevice_ai.md) — AIバックエンド（ローカル / Groq / OpenRouter）の準備・運用
- [`documents/groq_cloud.md`](documents/groq_cloud.md) — Cloud (Groq)
- [`documents/openrouter.md`](documents/openrouter.md) — OpenRouter LLM
- [`documents/opendroid-integration.md`](documents/opendroid-integration.md) — デジタルアシスタント統合
- [`documents/voice-harness-android-openrouter-plan.md`](documents/voice-harness-android-openrouter-plan.md) — OpenRouter・画面コンテキスト・対話 UI 実装計画（正本）
- [`documents/ondevice_gemma.md`](documents/ondevice_gemma.md) — Gemma 4 統合メモ
- [`documents/qwen_asr_encoder_issue.md`](documents/qwen_asr_encoder_issue.md) — Qwen3-ASR方式の調査・端末検証結果
