# Voice Harness

Android アプリ。XIAO nRF52840 Sense をウェアラブルマイクとして使い、端末内モデルで音声認識・AI応答を行い、Android TTSで読み上げる。

## 概要

```
[nRF52840 ジェスチャー検知]
        │ BLE 0x01 (録音開始通知)
        ▼
[Android: PCM 蓄積]
        │ BLE 0x02 (録音停止通知)
        ▼
[Silero VAD + FFT fallback]
        │ 音声あり
        ▼
[Qwen3-ASR / Gemma] → 文字起こし
        │
        ▼
[Qwen 3.5 / Gemma] → AI 応答
        │
        ▼
[Android TTS]      → 読み上げ
```

電話マイクでの録音も可能（BLE 未接続時のフォールバック）。

---

## セットアップ

### 必要なもの

- Android 7.0 以上 (API 24+)
- XIAO nRF52840 Sense（`harness-node/nordic-main` ファームウェア書き込み済み）
- QwenまたはGemmaのオンデバイスモデル（詳細は下記ドキュメント参照）

### アプリのインストール

```bash
git clone https://github.com/g150446/voice-harness-android.git
cd voice-harness-android
./scripts/prepare-qwen-asr-native.sh
./gradlew :app:installDebug
```

### 初期設定

1. アプリを起動し、要求された権限（Bluetooth・マイク・通知）を許可する
2. `models/`へモデルを配置し、`./scripts/push-all-models.sh`で端末へ転送する
3. 画面下部の **モデル設定** でQwenまたはGemmaを選択し、モデルを読み込む

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
3. 再度ジェスチャーを行うと録音停止 → `Silero VAD` で音声判定し、必要に応じて FFT フォールバック / rescue 判定を行ってからテキスト化・AI 応答・読み上げ

AI が読み上げ中に再度ジェスチャーを行うと、読み上げを中断して新しい対話を開始できる。

### 電話マイク録音（手動）

1. **● Record (Mic)** ボタンをタップして録音開始
2. **■ Stop** をタップして録音停止 → 自動でテキスト化・AI 応答・読み上げ

読み上げ中は **■ Stop Speaking** で中断し、すぐに新しい録音を開始できる。

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
adb logcat -s VoiceViewModel SileroVad BleManager BleConnectionService
```

### VAD 修正メモ

- BLE 経路は `Silero VAD` を優先し、`maxProb` が異常に低い場合は FFT ベース解析に自動フォールバックする
- FFT 側は無音フレームを除いた「アクティブフレーム」基準で判定する
- それでも境界値になる BLE 音声は `peakAfterDC` / `rmsAfterDC` / `maxBandRatio` に基づく rescue 条件で Groq 送信を継続する

### 主要ファイル

| ファイル | 役割 |
|---|---|
| `BleManager.kt` | BLE スキャン・接続・パケット解析 |
| `BleConnectionService.kt` | BLE をフォアグラウンドサービスとして管理 |
| `VoiceProcessor.kt` | 録音制御・VAD・オンデバイスAI・TTS |
| `ModelManager.kt` | モデル探索、取り込み、状態管理 |
| `QwenOnDeviceBackend.kt` | Qwen3-ASRとQwen 3.5の実行 |
| `GemmaOnDeviceBackend.kt` | Gemma 4の実行 |
| `BleSpeechDetector.kt` | BLE PCM の DC 除去、FFT フォールバック、スペクトル解析 |
| `MainActivity.kt` | UI（Jetpack Compose） |
| `GroqSettingsActivity.kt` | オンデバイスモデル設定画面 |

---

## 詳細ドキュメント

- [`documents/ble_protocol.md`](documents/ble_protocol.md) — BLE パケット仕様
- [`documents/ble_audio_reliability.md`](documents/ble_audio_reliability.md) — Bluetoothヘッドセット併用時の音声経路、PCM送達保証、障害調査
- [`documents/vad.md`](documents/vad.md) — Silero VAD / FFT フォールバックの仕様とチューニング
- [`documents/architecture.md`](documents/architecture.md) — アーキテクチャ詳細
- [`documents/ondevice_ai.md`](documents/ondevice_ai.md) — オンデバイスモデルの準備・運用
- [`documents/qwen_asr_encoder_issue.md`](documents/qwen_asr_encoder_issue.md) — Qwen3-ASR方式の調査・端末検証結果
