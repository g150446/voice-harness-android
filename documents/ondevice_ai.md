# オンデバイスAI構成とセットアップ

## 構成

ネットワーク上のSTT/LLM APIを使わず、音声認識と応答生成をAndroid端末内で処理する。
TTSはAndroid `TextToSpeech`を使用する。

| プロファイル | ASR | Chat | 用途 |
|---|---|---|---|
| Gemma（デフォルト） | Gemma 4 E2B LiteRT-LM audio | Qwen3-0.6B INT4（未配置時はGemma） | 高品質ASR |
| Qwen | Qwen3-ASR-0.6B Q8_0 GGUF | Qwen3-0.6B INT4（未配置時はQwen 3.5） | 高速 |

Qwen3-ASR自体がエンドツーエンドASRモデルなのでWhisperは併用しない。

## 必須モデル

`models/`へ次のファイルを配置する。モデルファイルはサイズが大きいためGit管理しない。

```text
models/
├── qwen35_mm_q8_ekv2048.litertlm
├── qwen3_0_6b_mixed_int4.litertlm
├── Qwen3-ASR-0.6B-Q8_0.gguf
├── mmproj-Qwen3-ASR-0.6B-Q8_0.gguf
└── gemma-4-E2B-it.litertlm
```

`qwen3_0_6b_mixed_int4.litertlm` は両プロファイル共通の高速Chatモデル。未配置時は
各プロファイルの従来Chatモデルへフォールバックする。

## ビルド

Qwen3-ASR対応済みの公式llama.cpp Android arm64 release `b9637`を使用する。

```bash
./scripts/prepare-qwen-asr-native.sh
./gradlew testDebugUnitTest assembleDebug
```

準備スクリプトはrelease archiveを`.qwen-asr-native/`へ展開する。このディレクトリは
Git対象外。別の配置を使う場合は`-PqwenAsrNativeDir=/absolute/path`を指定できる。
ネイティブランタイムはarm64-v8aのみで、x86_64 emulatorではQwen ASRを利用できない。

## 端末への配置

USBデバッグを有効にして端末を接続し、次を実行する。

```bash
./scripts/install-and-push.sh
```

APKを更新インストールし、モデルをアプリ専用の`files/models/`へコピーする。設定画面の
ファイル選択から個別に取り込むこともできる。

## 実行フロー

```text
BLE PCM → VAD → WAV → 選択中プロファイルのASR
        → conversation history → Chat → reminder tool → Android TTS
```

QwenではASRごとにllama.cpp CLIを分離プロセスとして起動する。高速Chat用LiteRT-LM
engineはGPU優先でbackendが保持し、GPU初期化に失敗した場合のみCPUへ切り替える。
設定画面でプロファイルを切り替えると既存engineを解放する。

## 状態とログ

ホーム画面とモデル設定画面に各モデルの状態とLoad/ASR/Chat時間を表示する。

```bash
adb logcat -s VoiceProcessor:D QwenOnDeviceBackend:D QwenAsrCli:D \
  GemmaOnDeviceBackend:D ModelManager:D
```

Qwen LLMのロード時に、含まれていないaudio encoderの警告が出る場合がある。音声は
Qwen3-ASRへ渡すため、Qwen LLMのロードが成功していれば致命的エラーではない。

## razr 50sでの確認結果

- Qwenの3モデル検出とLLMロード成功（約6.6秒）
- APK同梱ランタイムをアプリUIDで起動可能
- BLE取得済み日本語音声を「聞こえますか。」と認識（モデルロード込み約4.35秒）
- 17.44秒の英語音声も完走
- 短音声の推定host memoryは579 MiB

方式選定と旧encoder-only TFLiteの扱いは
[`qwen_asr_encoder_issue.md`](qwen_asr_encoder_issue.md)を参照。
