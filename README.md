# Voice Harness

Android アプリ。XIAO nRF52840 Sense をウェアラブルマイクとして使い、Groq API で音声認識・AI 応答・TTS 読み上げを行う。

## 概要

```
[nRF52840 ジェスチャー検知]
        │ BLE 0x01 (録音開始通知)
        ▼
[Android: PCM 蓄積]
        │ BLE 0x02 (録音停止通知)
        ▼
[スペクトル VAD (FFT)]
        │ 音声あり
        ▼
[Groq Whisper API] → 文字起こし
        │
        ▼
[Groq Chat API]    → AI 応答
        │
        ▼
[Android TTS]      → 読み上げ
```

電話マイクでの録音も可能（BLE 未接続時のフォールバック）。

---

## セットアップ

### 必要なもの

- Android 7.0 以上 (API 24+)
- XIAO nRF52840 Sense（`voice-bridge-ble/nrf52-handy` ファームウェア書き込み済み）
- [Groq API キー](https://console.groq.com/)

### アプリのインストール

```bash
git clone https://github.com/g150446/voice-harness-android.git
cd voice-harness-android
./gradlew :app:installDebug
```

### 初期設定

1. アプリを起動し、要求された権限（Bluetooth・マイク・通知）を許可する
2. 画面下部の **Settings** をタップ
3. Groq API キーを入力して保存

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
3. 再度ジェスチャーを行うと録音停止 → 自動でテキスト化・AI 応答・読み上げ

AI が読み上げ中に再度ジェスチャーを行うと、読み上げを中断して新しい対話を開始できる。

### 電話マイク録音（手動）

1. **● Record (Mic)** ボタンをタップして録音開始
2. **■ Stop** をタップして録音停止 → 自動でテキスト化・AI 応答・読み上げ

読み上げ中は **■ Stop Speaking** で中断し、すぐに新しい録音を開始できる。

---

## ビルド・開発

```bash
# デバッグビルド
./gradlew :app:assembleDebug

# デバイスへインストール
./gradlew :app:installDebug

# ログ確認（VAD・BLE）
adb logcat -s VoiceViewModel BleManager BleConnectionService
```

### 主要ファイル

| ファイル | 役割 |
|---|---|
| `BleManager.kt` | BLE スキャン・接続・パケット解析 |
| `BleConnectionService.kt` | BLE をフォアグラウンドサービスとして管理 |
| `VoiceViewModel.kt` | 録音制御・VAD・Groq API・TTS |
| `MainActivity.kt` | UI（Jetpack Compose） |
| `GroqSettingsActivity.kt` | API キー設定画面 |

---

## 詳細ドキュメント

- [`documents/ble_protocol.md`](documents/ble_protocol.md) — BLE パケット仕様
- [`documents/vad.md`](documents/vad.md) — スペクトル VAD アルゴリズムとチューニング
- [`documents/architecture.md`](documents/architecture.md) — アーキテクチャ詳細
