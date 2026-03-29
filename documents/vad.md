# VAD (Voice Activity Detection) 仕様

音声データを Groq に送信する前に「本当に音声が含まれているか」を判定する仕組み。
BLE 経路と電話マイク経路で異なるアルゴリズムを使用する。

## BLE 経路: Silero VAD + スペクトル VAD フォールバック

### 設計の背景

単純な RMS 振幅閾値では機能しない。nRF52840 のマイクは無音時でも電気的ノイズにより RMS ≈ 730 程度の信号を出力するため、閾値を高くすると小さな声も弾かれてしまう。

人声と電気的ノイズの違いを周波数特性で区別する:

通常は `Silero VAD`（ONNX Runtime）を優先し、BLE PCM を 512 サンプルずつ判定する。
ただし次のケースでは FFT ベースのスペクトル VAD に自動フォールバックする。

- Silero セッションの初期化に失敗した
- Silero 推論で例外が発生した
- `maxProb <= 0.01` のように全フレームが異常に低い確率に張り付いた
- FFT 比率だけでは閾値未満でも、`peakAfterDC` / `rmsAfterDC` / `maxBandRatio` が十分高ければ BLE 音声を救済する

### 1. Silero VAD（優先）

```
PCM データ
    │
    ▼
16-bit PCM → Float32 (-1..1)
    │
    ▼
DC オフセット除去
    │
    ▼
ピーク正規化（peak → 0.5）
    │
    ▼
Silero VAD（512 サンプル / frame）
    │
    ├─ speech frame ratio >= 5% → 音声あり
    │
    └─ maxProb が異常に低い / 例外 → FFT フォールバック
```

### 2. スペクトル VAD（フォールバック）

| 音の種類 | 300〜3400 Hz 帯域の割合 |
|---|---|
| 白色/ピンクノイズ（理論値） | ≈ 39% |
| nRF52840 電気的ノイズ | ≈ 39%（広帯域に分散） |
| 人声（母音・子音） | 50〜70% |

### FFT アルゴリズム

```
PCM データ
    │
    ▼
フレーム分割（512 サンプル = 32 ms、50% オーバーラップ）
    │
    ▼ 各フレーム
Hann 窓 適用
    │
    ▼
FFT（512 点、Cooley-Tukey）
    │
    ▼
スペクトル解析
  speech_energy = Σ|X[k]|² for k in [speechLowBin, speechHighBin]
  total_energy  = Σ|X[k]|² for k in [1, 255]
  ratio = speech_energy / total_energy
    │
    ▼
フレーム判定: ratio >= SPEECH_RATIO_THRESHOLD (0.45)
    │
    ▼
アクティブフレーム抽出: frameEnergy >= maxFrameEnergy * 0.1
    │
    ▼
音声判定: 音声フレーム数 / アクティブフレーム数 >= SPEECH_FRAME_MIN_RATIO (0.03)
```

### パラメータ

| 定数 | 値 | 場所 |
|---|---|---|
| `SILERO_SPEECH_THRESHOLD` | 0.50 | `VoiceViewModel.kt` |
| `SILERO_FRAME_MIN_RATIO` | 0.05 | `VoiceViewModel.kt` |
| `SILERO_STUCK_MAX_PROB` | 0.01 | `VoiceViewModel.kt` |
| `BLE_RESCUE_PEAK_THRESHOLD` | 0.08 | `VoiceViewModel.kt` |
| `BLE_RESCUE_RMS_THRESHOLD` | 0.015 | `VoiceViewModel.kt` |
| `BLE_RESCUE_BAND_RATIO_THRESHOLD` | 0.48 | `VoiceViewModel.kt` |
| `SPEECH_RATIO_THRESHOLD` | 0.45 | `BleSpeechDetector.kt` |
| `SPEECH_FRAME_MIN_RATIO` | 0.03 | `BleSpeechDetector.kt` |
| `ACTIVE_FRAME_ENERGY_RATIO` | 0.10 | `BleSpeechDetector.kt` |

- `SPEECH_RATIO_THRESHOLD`: ノイズ比率 ≈ 0.39 より少し高い 0.45 に下げ、弱い BLE 音声でも拾いやすくする
- `SPEECH_FRAME_MIN_RATIO`: アクティブフレームの 3% 以上が音声フレームなら「音声あり」と判定
- `ACTIVE_FRAME_ENERGY_RATIO`: 無音フレームで比率が薄まらないよう、最大エネルギーの 10% 未満のフレームは母数から除外する
- `BLE_RESCUE_*`: Silero が壊れていて FFT 比率が境界値でも、BLE PCM の振幅と帯域集中度が十分なら Groq 送信を継続する

### 実装メモ

- Silero ラッパー: `app/src/main/java/com/g150446/voiceharness/SileroVad.kt`
- FFT フォールバック: `app/src/main/java/com/g150446/voiceharness/BleSpeechDetector.kt`
- 外部ライブラリなし、Kotlin 純実装の Cooley-Tukey 基数2 FFT (`fftInPlace` 関数)

- フレームサイズ: 512（2の累乗必須）
- Hann 窓でスペクトルリーケージを低減
- 処理は録音終了後に一括実行（リアルタイム不要）

### チューニング方法

logcat で実際の値を確認する:

```bash
adb logcat -s VoiceViewModel SileroVad
```

出力例:
```
Silero VAD: 8/47 speech frames (17.0%), maxProb=0.842
Spectrum VAD fallback: reason=Silero output stuck near zero, speechFrames=12/31 active (47 total, 38.7%), maxBandRatio=0.711, topBandRatios=[0.711, 0.684, 0.633, 0.598, 0.577]
```

| 問題 | 対処 |
|---|---|
| 無音が通過する | `SPEECH_RATIO_THRESHOLD` を 0.45 → 0.50 に上げる、または `ACTIVE_FRAME_ENERGY_RATIO` を 0.10 → 0.15 に上げる |
| 声が弾かれる | `SPEECH_RATIO_THRESHOLD` を 0.45 → 0.42 に下げる、または `SPEECH_FRAME_MIN_RATIO` を 0.03 → 0.02 に下げる |

## 電話マイク経路: 振幅 VAD

Android `MediaRecorder.getMaxAmplitude()` を 100 ms ごとにポーリングし、録音中のピーク振幅を追跡する。

| 定数 | 値 | 説明 |
|---|---|---|
| `VAD_AMPLITUDE_THRESHOLD` | 500 | 0〜32767 の範囲で無音と判断する上限 |

録音停止時に `maxAmplitudeSeen < 500` であれば Groq 送信をスキップして READY に戻る。
