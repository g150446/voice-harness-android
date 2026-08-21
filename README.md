# Voice Harness

Android アプリ。XIAO nRF52840 Sense をウェアラブルマイクとして使い、オンデバイスまたは Groq API で音声認識・AI応答を行い、Android TTS（または Vuzix Z100）で出力する。

## 概要

```
[nRF52840 ジェスチャー検知]
        │ BLE 0x01 (録音開始通知)
        ▼
[Android: PCM 蓄積 + ストリーミング VAD]
        │ BLE 0x02 または 無音5秒 → RX 0x00
        ▼
[Silero VAD + FFT fallback]
        │ 音声あり
        ▼
[ASR: Gemma / Qwen3-ASR / Groq Whisper] → 文字起こし
        │
        ▼
[Chat: Gemma / LFM 2.5 / Groq Chat] → AI 応答
        │
        ▼
[Android TTS or Z100] → 出力
```

プロファイルは **モデル設定** で切替:

| プロファイル | ASR | Chat |
|---|---|---|
| 高品質 (Gemma 4 E2B) | Gemma | Gemma |
| Qwen ASR + LFM 2.5 | Qwen3-ASR | LFM 2.5 |
| Cloud (Groq) | Whisper | gpt-oss-120b |

電話マイクでの録音も可能（BLE 未接続時のフォールバック）。

---

## セットアップ

### 必要なもの

- Android 12 以上 (API 31+)
- XIAO nRF52840 Sense（`harness-node/nordic-main` ファームウェア書き込み済み）
- ローカル利用時: Qwen / Gemma / LFM のオンデバイスモデル（詳細は下記ドキュメント参照）
- クラウド利用時: Groq API キー（モデルファイル不要）
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
2. 画面下部の **モデル設定** でプロファイルを選ぶ
   - **ローカル**: `models/` を配置し `./scripts/push-all-models.sh`、または画面から取り込み → モデルを読み込む
   - **Cloud (Groq)**: API キーを入力して保存

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

AI が読み上げ中に再度ジェスチャーを行うと、読み上げを中断して新しい対話を開始できる。

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

### VAD / ASR 幻覚対策メモ

- BLE 経路は `Silero VAD` を優先し、`maxProb` が異常に低い場合は FFT ベース解析に自動フォールバックする
- FFT 側は無音フレームを除いた「アクティブフレーム」基準で判定する
- Silero が stuck のときだけ、小声の実測振幅以上ならエネルギー救済する。無音ノイズは拒否する
- 録音中に無音が 5 秒続くと Android が RX `0x00` で録音を止める
- `ちいかわ` 系語彙は 1 パス目に載せない。転写に `アニメ` があるときだけ 2 パス目で Preferred spellings を付与する
- `AsrTextFilter` が語彙エコー（アニメ無しの3語列挙）と儀礼句だけの幻覚（`はい、ありがとうございます` など）を破棄する

### 主要ファイル

| ファイル | 役割 |
|---|---|
| `BleManager.kt` | BLE スキャン・接続・パケット解析 |
| `BleConnectionService.kt` | BLE をフォアグラウンドサービスとして管理 |
| `VoiceProcessor.kt` | 録音制御・ストリーミング VAD・AI・TTS |
| `SilenceEndpointTracker.kt` | 録音中の連続無音 5 秒判定 |
| `AsrVocabulary.kt` | ASR Preferred spellings とトリガー語（アニメ） |
| `AsrTextFilter.kt` | 語彙エコー・儀礼句など ASR 幻覚の破棄 |
| `ModelManager.kt` | モデル探索、取り込み、状態管理 |
| `OnDeviceAiFacade.kt` | プロファイル切替（Gemma / Qwen / Groq） |
| `QwenOnDeviceBackend.kt` | Qwen3-ASR + LFM 2.5（LEAP） |
| `GemmaOnDeviceBackend.kt` | Gemma 4 の実行 |
| `GroqVoiceAiBackend.kt` | Groq Whisper + Chat Completions |
| `GroqPrefs.kt` | Groq API キー保存 |
| `BleSpeechDetector.kt` | BLE PCM の DC 除去、FFT フォールバック、スペクトル解析 |
| `SmartGlassesOutputManager.kt` | Vuzix Z100の状態監視、制御取得、返答全文表示 |
| `MainActivity.kt` | UI（Jetpack Compose） |
| `GroqSettingsActivity.kt` | モデル設定画面（ローカル + Groq） |

---

## 詳細ドキュメント

- [`documents/ble_protocol.md`](documents/ble_protocol.md) — BLE パケット仕様
- [`documents/ble_audio_reliability.md`](documents/ble_audio_reliability.md) — Bluetoothヘッドセット併用時の音声経路、PCM送達保証、障害調査
- [`documents/smart_glasses_output.md`](documents/smart_glasses_output.md) — Vuzix Z100へのAI返答出力とフォールバック仕様
- [`documents/vad.md`](documents/vad.md) — Silero VAD / FFT フォールバックの仕様とチューニング
- [`documents/architecture.md`](documents/architecture.md) — アーキテクチャ詳細
- [`documents/ondevice_ai.md`](documents/ondevice_ai.md) — AIバックエンド（ローカル / Groq）の準備・運用
- [`documents/groq_cloud.md`](documents/groq_cloud.md) — Cloud (Groq) プロファイル
- [`documents/ondevice_gemma.md`](documents/ondevice_gemma.md) — Gemma 4 統合メモ
- [`documents/qwen_asr_encoder_issue.md`](documents/qwen_asr_encoder_issue.md) — Qwen3-ASR方式の調査・端末検証結果
