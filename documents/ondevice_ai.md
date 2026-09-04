# AIバックエンド構成とセットアップ

## 構成

音声認識（ASR）と応答生成（LLM）は **独立に選択** する。  
旧「プロファイル」（Gemma / Qwen / Groq のセット）は初回起動時に ASR・LLM 双方へ冪等コピーされ、以降は独立キーが正本。

| 役割 | 選択肢 | 備考 |
|---|---|---|
| ASR | Gemma 4 E2B / Qwen3-ASR / Groq Whisper | |
| LLM | Gemma 4 E2B / LFM 2.5 / Groq Chat / **OpenRouter** | OpenRouter は LLM のみ |

TTS は Android `TextToSpeech`（または Even Realities G2 表示）。  
ローカルはネットワーク不要。Groq / OpenRouter はインターネットと API キーが必要。

同じローカルモデルが ASR と LLM の両方で選ばれても、`BackendRegistry` が二重ロードしない。  
設定変更で不要になったバックエンドだけ解放する（ASR 変更で進行中 LLM 会話を落とさない）。

詳細:

- ローカル Gemma: [`ondevice_gemma.md`](ondevice_gemma.md)
- クラウド Groq: [`groq_cloud.md`](groq_cloud.md)
- OpenRouter: [`openrouter.md`](openrouter.md)
- デジタルアシスタント: [`opendroid-integration.md`](opendroid-integration.md)
- 実装計画: [`voice-harness-android-openrouter-plan.md`](voice-harness-android-openrouter-plan.md)
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

Groq / OpenRouter のみ使う場合、上記モデルは不要。

## ビルド

Qwen3-ASR 対応済みの公式 llama.cpp Android arm64 release `b9637` を使用する。

```bash
./scripts/prepare-qwen-asr-native.sh
./scripts/install-usb.sh
```

### Tailscale 経由の ADB（例: razr 50s）

**APK インストールは USB 推奨**（`./scripts/install-usb.sh`）。巨大 debug APK を
Tailscale 経由で入れるとタイムアウトし、古いプロセスが残ることがある。

一度 USB で `adb tcpip 5555` を有効化したあと（logcat 等）:

```bash
./scripts/adb-tailscale.sh connect
adb logcat -s VoiceProcessor BleManager
```

再起動後に 5555 が閉じた場合は、再度 USB で `./scripts/adb-tailscale.sh enable-usb`。
## 実行フロー

### HarnessNode（BLE）

```text
BLE PCM → VAD → WAV
        → SttBackend.transcribe
             （ローカル: ASR 1パス → 必要なら語彙2パス）
             （Groq: Whisper 1回）
        → AsrTextFilter → conversation history
        → LlmBackend.chat(ChatRequest) → reminder tool → TTS / Even G2
```

画面コンテキストは **送らない**（Gateway でも防御的に破棄）。

### デジタルアシスタント（電源長押し）

```text
ROLE_ASSISTANT → VoiceInteractionSession（デフォルト UI 無効）
              → HarnessAssistantActivity（下部シート）
              → ユーザーがテキスト or マイクで送信
              → 任意 ScreenContext（AssistStructure + JPEG）
              → AssistantGateway → LlmBackend.chat
              → マイク入力のみ TTS（テキスト入力は原則 TTS しない）
```

`ちいかわ` / `ハチワレ` / `うさぎ` は通常の Preferred spellings には載せない。  
転写に `アニメ` があるときだけ 2 パス目で載せる（ローカル ASR のみ）。詳細は
[`ondevice_gemma.md`](ondevice_gemma.md) と [`vad.md`](vad.md) を参照。

Qwen では ASR ごとに llama.cpp CLI を分離プロセスとして起動し、Chat は LEAP の LFM 2.5。

## 状態とログ

モデル設定画面に ASR / LLM それぞれの選択と Load/ASR/Chat 時間を表示する。

```bash
adb logcat -s VoiceProcessor:D QwenOnDeviceBackend:D QwenAsrCli:D \
  GemmaOnDeviceBackend:D GroqVoiceAiBackend:D OpenRouterLlmBackend:D \
  ModelManager:D OnDeviceAiFacade:D AssistantSessionCtrl:D HarnessVoiceSession:D
```

## razr 50s での確認結果（ローカル）

- Qwen のモデル検出と LLM ロード成功
- APK 同梱ランタイムをアプリ UID で起動可能
- BLE 取得済み日本語音声の認識完走
- Gemma / LFM の詳細は各ドキュメント参照
- デジタルアシスタント headless 経路の受け入れ結果は [`opendroid-integration.md`](opendroid-integration.md)

方式選定と旧 encoder-only TFLite の扱いは
[`qwen_asr_encoder_issue.md`](qwen_asr_encoder_issue.md) を参照。
