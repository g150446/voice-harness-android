# VAD (Voice Activity Detection) 仕様

音声データを ASR に渡す前に「本当に音声が含まれているか」を判定する仕組み。  
現行アプリでは音声入力を BLE デバイスのみに統一しており、VAD も BLE PCM に対してのみ実行する。

無音や電気ノイズを ASR に渡すと、プロンプト語彙（例: ちいかわ / ハチワレ / うさぎ）を幻覚することがある。VAD はそれを録音中と録音後の二段で防ぐ。

## BLE 経路: Silero VAD + スペクトル VAD フォールバック

### 設計の背景

単純な RMS 振幅閾値では機能しない。nRF52840 のマイクは無音時でも電気的ノイズにより RMS ≈ 730 程度の信号を出力するため、閾値を高くすると小さな声も弾かれてしまう。

人声と電気的ノイズの違いを周波数特性で区別する:

通常は `Silero VAD`（ONNX Runtime）を優先し、BLE PCM を 512 サンプルずつ判定する。
ただし次のケースでは FFT ベースのスペクトル VAD に自動フォールバックし、囁き声のような弱い音声を再評価する。

- Silero セッションの初期化に失敗した
- Silero 推論で例外が発生した
- Silero の通常推論は完了したが、`speech frame ratio < 5%` で音声判定に届かなかった
- `maxProb <= 0.05` のように全フレームが異常に低い確率に張り付いた
- Silero が stuck のときだけ、`peakAfterDC` / `rmsAfterDC` が小声の実測値以上ならエネルギー救済する
- Silero が通常どおり非音声と判定したクリップは、振幅だけでは救わない（スペクトル経路は残す）

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
ピーク正規化（peak → 0.5、gain 上限 10x）
    │
    ▼
Silero VAD（512 サンプル / frame）
    │
    ├─ speech frame ratio >= 5% → 音声あり
    │
    └─ 判定に届かない / maxProb が異常に低い / 例外 → FFT フォールバック
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
| `SILERO_SPEECH_THRESHOLD` | 0.50 | `BleSpeechPolicy.kt` |
| `SILERO_FRAME_MIN_RATIO` | 0.05 | `BleSpeechPolicy.kt` |
| `SILERO_STUCK_MAX_PROB` | 0.05 | `BleSpeechPolicy.kt` |
| `BLE_RESCUE_PEAK_THRESHOLD` | 0.03 | `BleSpeechPolicy.kt` |
| `BLE_RESCUE_RMS_THRESHOLD` | 0.006 | `BleSpeechPolicy.kt` |
| `BLE_RESCUE_BAND_RATIO_THRESHOLD` | 0.35 | `BleSpeechPolicy.kt` |
| `BLE_ENERGY_RESCUE_PEAK_THRESHOLD` | 0.015 | `BleSpeechPolicy.kt` |
| `BLE_ENERGY_RESCUE_RMS_THRESHOLD` | 0.004 | `BleSpeechPolicy.kt` |
| `BLE_SILENCE_STOP_MS` | 5000 | `BleSpeechPolicy.kt` |
| `SILERO_MAX_GAIN` | 10 | `BleSpeechDetector.kt` |
| `SPEECH_RATIO_THRESHOLD` | 0.45 | `BleSpeechDetector.kt` |
| `SPEECH_FRAME_MIN_RATIO` | 0.03 | `BleSpeechDetector.kt` |
| `ACTIVE_FRAME_ENERGY_RATIO` | 0.10 | `BleSpeechDetector.kt` |

- `SPEECH_RATIO_THRESHOLD`: ノイズ比率 ≈ 0.39 より少し高い 0.45 に下げ、弱い BLE 音声でも拾いやすくする
- `SPEECH_FRAME_MIN_RATIO`: アクティブフレームの 3% 以上が音声フレームなら「音声あり」と判定
- `ACTIVE_FRAME_ENERGY_RATIO`: 無音フレームで比率が薄まらないよう、最大エネルギーの 10% 未満のフレームは母数から除外する
- `BLE_ENERGY_RESCUE_*`: Silero が stuck のときだけ使う。無音（peak≈0.005 / rms≈0.0012）は拒否し、小声実測（peak=0.0215 / rms=0.0138）は通す
- `BLE_RESCUE_*`: Silero が stuck ではなく、スペクトル比と振幅が十分なら救済する
- `SILERO_MAX_GAIN`: 無音ピークを 100 倍して Silero に渡さない
- `BLE_SILENCE_STOP_MS`: 録音中に無音が 5 秒続くと Android が RX `0x00` で停止する

### 実装メモ

- Silero ラッパー: `app/src/main/java/com/g150446/voiceharness/SileroVad.kt`
- FFT フォールバック: `app/src/main/java/com/g150446/voiceharness/BleSpeechDetector.kt`
- 無音エンドポイント: `app/src/main/java/com/g150446/voiceharness/SilenceEndpointTracker.kt`
- 外部ライブラリなし、Kotlin 純実装の Cooley-Tukey 基数2 FFT (`fftInPlace` 関数)

- フレームサイズ: 512（2の累乗必須）
- Hann 窓でスペクトルリーケージを低減
- 録音中は 512 サンプルごとにストリーミング Silero で無音 5 秒を監視する
- 録音終了後にクリップ全体へ Silero + スペクトル VAD を一括実行する

### 録音中の無音エンドポイント

```
PCM パケット到着
    │
    ▼
512 サンプルに組み立て
    │
    ▼
DC 除去 + gain cap + Silero predict
    │
    ├─ prob > 0.5 → 連続無音カウンタをリセット
    │
    └─ 非音声が連続 5 秒（157 フレーム） → RX 0x00 で録音停止
                                           後続の TX 0x02 は無視
```

- 録音開始直後の無音も同じ 5 秒で止める
- 発話のあとの無音 5 秒でも止める
- 停止後は通常どおりクリップ全体の VAD を実行し、無音なら ASR をスキップする

### チューニング方法

logcat で実際の値を確認する:

```bash
adb logcat -s VoiceProcessor SileroVad
```

出力例:
```
Silero VAD: 8/47 speech frames (17.0%), maxProb=0.842
Spectrum VAD fallback: reason=Silero output stuck near zero, speechFrames=12/31 active (47 total, 38.7%), maxBandRatio=0.711, topBandRatios=[0.711, 0.684, 0.633, 0.598, 0.577]
```

| 問題 | 対処 |
|---|---|
| 無音が通過する | `BLE_ENERGY_RESCUE_RMS_THRESHOLD` を上げる、または `SILERO_FRAME_MIN_RATIO` を上げる |
| 声が弾かれる | `BLE_ENERGY_RESCUE_*` を少し下げる、または `SPEECH_FRAME_MIN_RATIO` を 0.03 → 0.02 に下げる |

## 補足

以前は電話マイク経路に対して振幅ベースの簡易 VAD を持っていたが、現在は削除済み。  
録音開始はファームウェアのジェスチャー（`0x01`）が担う。停止はジェスチャー（`0x02`）に加え、Android が無音 5 秒を検出したとき RX `0x00` で要求する。
