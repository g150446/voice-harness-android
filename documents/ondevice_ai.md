# AIバックエンド構成とセットアップ

## 構成

音声認識と応答生成は、設定画面のプロファイルで切り替える。

| プロファイル | ASR | Chat | 用途 |
|---|---|---|---|
| Gemma（デフォルト） | Gemma 4 E2B LiteRT-LM audio | 同一 Gemma engine | 高品質・オフライン |
| Qwen | Qwen3-ASR-0.6B Q8_0 GGUF | LFM 2.5 2.6B（LEAP） | オフライン代替 |
| Cloud (Groq) | Whisper `whisper-large-v3-turbo` | `openai/gpt-oss-120b` | クラウド・端末負荷なし |

TTS は Android `TextToSpeech`（または Vuzix Z100 表示）。  
ローカルプロファイルはネットワーク不要。Groq はインターネットと API キーが必要。

詳細:

- ローカル Gemma: [`ondevice_gemma.md`](ondevice_gemma.md)
- クラウド Groq: [`groq_cloud.md`](groq_cloud.md)
- Qwen ASR + LFM 検証: [`lfm25_qwen_asr_validation.md`](lfm25_qwen_asr_validation.md)

## 必須モデル（ローカルのみ）

`models/` へ次のファイルを配置する。モデルファイルはサイズが大きいため Git 管理しない。

```text
models/
├── gemma-4-E2B-it.litertlm
├── Qwen3-ASR-0.6B-Q8_0.gguf
├── mmproj-Qwen3-ASR-0.6B-Q8_0.gguf
└── LFM2.5-2.6B-Q4_K_M.gguf
```

Groq のみ使う場合、上記モデルは不要。

## ビルド

Qwen3-ASR 対応済みの公式 llama.cpp Android arm64 release `b9637` を使用する。

```bash
./scripts/prepare-qwen-asr-native.sh
./gradlew testDebugUnitTest assembleDebug
```

準備スクリプトは release archive を `.qwen-asr-native/` へ展開する。このディレクトリは
Git 対象外。別の配置を使う場合は `-PqwenAsrNativeDir=/absolute/path` を指定できる。
ネイティブランタイムは arm64-v8a のみで、x86_64 emulator では Qwen ASR を利用できない。

## 端末への配置

USB デバッグを有効にして端末を接続し、次を実行する。

```bash
./scripts/install-and-push.sh
```

APK を更新インストールし、モデルをアプリ専用の `files/models/` へコピーする。設定画面の
ファイル選択から個別に取り込むこともできる。

### Tailscale 経由の ADB（例: razr 50s）

一度 USB で `adb tcpip 5555` を有効化したあと:

```bash
adb connect 100.102.210.64:5555   # motorola-razr-50s の Tailscale IP
./gradlew :app:installDebug
```

再起動後に 5555 が閉じた場合は、再度 USB で `adb tcpip 5555` が必要。

## 実行フロー

```text
BLE PCM → VAD → WAV
        → VoiceAiBackend.transcribe
             （ローカル: ASR 1パス → 必要なら語彙2パス）
             （Groq: Whisper 1回）
        → AsrTextFilter → conversation history
        → VoiceAiBackend.chat → reminder tool → TTS / Z100
```

`ちいかわ` / `ハチワレ` / `うさぎ` は通常の Preferred spellings には載せない。
転写に `アニメ` があるときだけ 2 パス目で載せる（ローカル ASR のみ）。詳細は
[`ondevice_gemma.md`](ondevice_gemma.md) と [`vad.md`](vad.md) を参照。

Qwen では ASR ごとに llama.cpp CLI を分離プロセスとして起動し、Chat は LEAP の LFM 2.5。
設定画面でプロファイルを切り替えると既存 engine を解放する（Groq は HTTP のみで no-op）。

## 状態とログ

ホーム画面とモデル設定画面に各モデルの状態と Load/ASR/Chat 時間を表示する。

```bash
adb logcat -s VoiceProcessor:D QwenOnDeviceBackend:D QwenAsrCli:D \
  GemmaOnDeviceBackend:D GroqVoiceAiBackend:D ModelManager:D OnDeviceAiFacade:D
```

## razr 50s での確認結果（ローカル）

- Qwen のモデル検出と LLM ロード成功
- APK 同梱ランタイムをアプリ UID で起動可能
- BLE 取得済み日本語音声の認識完走
- Gemma / LFM の詳細は各ドキュメント参照

方式選定と旧 encoder-only TFLite の扱いは
[`qwen_asr_encoder_issue.md`](qwen_asr_encoder_issue.md) を参照。
