# Qwen3-ASR Android implementation decision

## Decision

Qwenプロファイルの音声認識には、Whisperを併用せず、完全な
`Qwen3-ASR-0.6B`を使用する。会話生成は引き続きQwen 3.5 LiteRT-LMが担当する。

```text
BLE PCM → WAV → Qwen3-ASR GGUF → text → Qwen 3.5 chat → Android TTS
```

必須ファイルは次の3個。

- `Qwen3-ASR-0.6B-Q8_0.gguf`（decoder）
- `mmproj-Qwen3-ASR-0.6B-Q8_0.gguf`（audio projector）
- `qwen35_mm_q8_ekv2048.litertlm`（chat）

ASRランタイムにはQwen3-ASR対応済みの公式llama.cpp Android arm64 release
`b9637`を固定して使用する。`scripts/prepare-qwen-asr-native.sh`で準備し、ビルド時に
必要ライブラリとCLIをAPKへ同梱する。

## Why the old TFLite file cannot work alone

`qwen3_asr_0.6b_5s_i8.tflite`の確認済みsignatureは以下。

- Input: `[1, 128, 500]`, `FLOAT32`
- Output: `[1, 70, 1024]`, `FLOAT32`

これはaudio encoderのみで、出力は文字列ではなくaudio embeddingである。decoderと
tokenizerを欠くため単体ASRにはならない。Qwen 3.5 `.litertlm`の音声機能も対象モデルに
audio encoderがなく、端末では`TF_LITE_AUDIO_ENCODER_HW not found`になった。

## Device result

Motorola razr 50s（arm64）で、APK内ランタイムをアプリUIDから起動して確認した。

- 日本語合成音声: `明日の朝七時に起こして。`
- BLEで取得した実音声: `聞こえますか。`
- 17.44秒の英語音声も完走
- 短い実音声はモデルロード込み約4.35秒
- 推定host memory: 短音声579 MiB、17.44秒音声682 MiB

## Build and install

```bash
./scripts/prepare-qwen-asr-native.sh
./gradlew testDebugUnitTest assembleDebug
./scripts/install-and-push.sh
```

モデルはGit管理せず`models/`へ配置する。設定画面から3ファイルを個別に取り込むことも
できる。x86_64端末ではQwen3-ASRネイティブランタイムは利用できない。
