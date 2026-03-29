# VAD (Voice Activity Detection) 仕様

音声データを Groq に送信する前に「本当に音声が含まれているか」を判定する仕組み。
BLE 経路と電話マイク経路で異なるアルゴリズムを使用する。

## BLE 経路: スペクトル VAD（FFT ベース）

### 設計の背景

単純な RMS 振幅閾値では機能しない。nRF52840 のマイクは無音時でも電気的ノイズにより RMS ≈ 730 程度の信号を出力するため、閾値を高くすると小さな声も弾かれてしまう。

人声と電気的ノイズの違いを周波数特性で区別する:

| 音の種類 | 300〜3400 Hz 帯域の割合 |
|---|---|
| 白色/ピンクノイズ（理論値） | ≈ 39% |
| nRF52840 電気的ノイズ | ≈ 39%（広帯域に分散） |
| 人声（母音・子音） | 50〜70% |

### アルゴリズム

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
フレーム判定: ratio > SPEECH_RATIO_THRESHOLD (0.50)
    │
    ▼
音声判定: 音声フレーム数 / 総フレーム数 >= SPEECH_FRAME_MIN_RATIO (0.05)
```

### パラメータ

| 定数 | 値 | 場所 |
|---|---|---|
| `SPEECH_LOW_HZ` | 300 | `VoiceViewModel.kt` |
| `SPEECH_HIGH_HZ` | 3400 | `VoiceViewModel.kt` |
| `SPEECH_RATIO_THRESHOLD` | 0.50 | `VoiceViewModel.kt` |
| `SPEECH_FRAME_MIN_RATIO` | 0.05 | `VoiceViewModel.kt` |

- `SPEECH_RATIO_THRESHOLD`: ノイズ比率 ≈ 0.39 より高く、人声 ≈ 0.55 より低い 0.50 に設定
- `SPEECH_FRAME_MIN_RATIO`: 全フレームの 5% 以上が音声フレームなら「音声あり」と判定

### FFT 実装

外部ライブラリなし、Kotlin 純実装の Cooley-Tukey 基数2 FFT (`fftInPlace` 関数)。

- フレームサイズ: 512（2の累乗必須）
- Hann 窓でスペクトルリーケージを低減
- 処理は録音終了後に一括実行（リアルタイム不要）

### チューニング方法

logcat で実際の値を確認する:

```bash
adb logcat -s VoiceViewModel
```

出力例:
```
VAD: 12/47 speech frames (25.5%, threshold=5%)   # 音声あり → Groq 送信
VAD: 0/38 speech frames (0.0%, threshold=5%)      # 無音 → スキップ
```

| 問題 | 対処 |
|---|---|
| 無音が通過する | `SPEECH_RATIO_THRESHOLD` を 0.50 → 0.55 に上げる |
| 声が弾かれる | `SPEECH_RATIO_THRESHOLD` を 0.50 → 0.45 に下げる、または `SPEECH_FRAME_MIN_RATIO` を 0.05 → 0.03 に下げる |

## 電話マイク経路: 振幅 VAD

Android `MediaRecorder.getMaxAmplitude()` を 100 ms ごとにポーリングし、録音中のピーク振幅を追跡する。

| 定数 | 値 | 説明 |
|---|---|---|
| `VAD_AMPLITUDE_THRESHOLD` | 500 | 0〜32767 の範囲で無音と判断する上限 |

録音停止時に `maxAmplitudeSeen < 500` であれば Groq 送信をスキップして READY に戻る。
