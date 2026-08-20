# Qwen ASR + LFM 2.5 2.6B 実機検証

> 実施日: 2026-08-19 / 端末: motorola razr 50s（Mali-G615）/ Tailscale ADB

プラン「Qwen ASR 品質 → LFM thinking オフ速度 → reminder ツール」の検証結果。
Chat スロットの差し替えは、下記のブロッカーが残るため未実施。

## 1. Qwen ASR 品質（完了）

プロファイルは端末上ですでに `QWEN`。TTS（Kyoko, 16 kHz WAV）をアプリ UID から
`libqwen_asr_cli.so` に渡し、本番と同じ引数（`-n 64 -c 1024 -t 4 -ngl 0 --temp 0`）で実行した。

| 入力（読み上げ） | 出力 | 壁時計 | 判定 |
|---|---|---|---|
| こんにちは | `こんにちは。` | 6 s（初回ロード込み） | OK |
| 聞こえますか | `聞こえますか。` | 4 s | OK |
| ちいかわ、ハチワレ、うさぎ | `チカは八割、うさぎ。` | 5 s | NG（固有名詞） |
| 明日の朝七時に起こして | `明日の朝七時に起こして。` | 4 s | OK |

短い日本語とリマインダー文は十分。同じフレーズを Gemma ASR は BLE 実音声で正しく取れている（17:01 logcat）。

ASR 単体は Gemma 実測（約 4.3–5.0 s）と同程度。E2E の半分は残る。

### 1.1 固有名詞プロンプト（2026-08-19 追試）

同じ `chiikawa.wav` / `short.wav`（Kyoko 16 kHz）と本番 CLI フラグで、`-p` / `-sys` だけ変えた。
合格条件: 固有名詞クリップに `ちいかわ` と `ハチワレ` が両方出ること。うさぎだけでは不合格。

| 条件 | プロンプト | ちいかわ… 出力 | こんにちは | 判定 |
|---|---|---|---|---|
| A 現行 | `-p` 固定文のみ | `チカは八割、うさぎ。` | `こんにちは。` | NG |
| B Gemma同等 | `-p` に `AsrPromptBuilder.build(JAPANESE)` 全文（Preferred spellings 付き） | `ちいかわ、ハチワレ、うさぎ` | `こんにちは。` | **合格** |
| C Qwen公式 | `-sys "Vocabulary: ちいかわ, ハチワレ, うさぎ"` + 現行 `-p` | `ちいかわは八割うさぎ` | `こんにちは。` | NG（ハチワレ欠） |

対照クリップはどの条件でも語彙語を出さなかった（過バイアスなし）。壁時計は 4–6 s で条件差なし。

**推奨:** llama-mtmd-cli 経路では Qwen 公式の短い `-sys Vocabulary:` より、Gemma と同じ長文を **user `-p`** に載せる方が効く。`QwenAsrCli` へ配線するなら `AsrPromptBuilder` をそのまま `-p` に渡すのが本線。アプリ配線自体はこの追試では未実施。

## 2. LFM 2.5 2.6B thinking オフ速度

### razr 上の tok/s

未計測。`LFM2.5-2.6B-Q4_K_M.gguf`（1.60 GB）と `LFM2.5-2.6B_int4.litertlm`（1.67 GB）の
取得が Hugging Face 経由で約 110 KB/s（完了まで約 4 時間）のため、セッション内に
端末へ載せられなかった。`llama-cli` と `libllama-cli-impl.so`（llama.cpp b9637）は
`/data/local/tmp/` に配置済み。GGUF 到着後:

```bash
export LD_LIBRARY_PATH=/data/local/tmp:/data/app/~~…/com.g150446.voiceharness-…/lib/arm64
/data/local/tmp/llama-cli -m LFM2.5-2.6B-Q4_K_M.gguf -c 2048 -n 64 -t 4 -ngl 0 \
  --chat-template-kwargs '{"enable_thinking":false}' \
  -p 'Reply in Japanese in one short sentence. 東京都は日本の首都ですか？'
```

### thinking を切れるか（LiteRT 成果物）

[litert-community/LFM2.5-2.6B](https://huggingface.co/litert-community/LFM2.5-2.6B) の変換手順は、
量子化ビルドで **generation prompt に thought ブロックを prefills** している。切ると int4 が
数千トークンの暴走 deliberation になる、と実測されている。

つまり DedicatedChatEngine の `extraContext = enable_thinking: false`（Gemma 向け）を
そのまま LFM に付けても、成果物側が thinking を前提にしている。声の待ち時間では
「thinking なし LFM」は LiteRT 経路では期待しにくい。

llama.cpp GGUF ならテンプレート側で切れる可能性はある（未実測）。

### 他端末の公開値（参考、razr ではない）

Pixel 8a / LiteRT LFM2.5 系: GPU prefill は速い（~190 tok/s）が decode は帯域律速で
CPU とほぼ同じ **約 21 tok/s**。Mac M4 Max CPU の int4 は 43.7 decode tok/s。
短文 40 トークンなら decode 約 2 s。Gemma Chat 実測 ~5 s より短い見込みだが、
thinking が乗ると逆転する。

## 3. set_reminder ツール

| 経路 | 見込み |
|---|---|
| 現行 LiteRT `ReminderToolSet`（JSON / Kotlin `tool()`） | Gemma と Qwen Fast Chat で使用中。LFM の native 形式は Pythonic で、同じ `tool()` に乗る保証はない。 |
| litert-community LFM の chat template | ツール呼び出し用に書き換え済み、かつ thought channel 必須。LiteRT の constrained decoding に乗るかは実機未確認。 |
| llama.cpp | `--jinja` + tools JSON で LFM 公式形式を渡すのが本線。GGUF 未配置のため未実行。 |

reminder が1発で呼べないなら、LFM を載せる利点は会話品質だけになり、速度目的では弱い。

## 判断

- **Qwen ASR 自体は短い日本語・リマインダー文では使える。** 固有名詞は現行 `-p` では弱いが、Gemma と同じ `AsrPromptBuilder` 全文を `-p` に載せると `ちいかわ` / `ハチワレ` は通る。公式 `-sys Vocabulary:` だけでは不十分だった。
- **LiteRT の LFM2.5-2.6B を「thinking オフで速くする」前提は、公開変換パイプラインと矛盾する。**
- Chat 差し替えは、GGUF を razr に載せて thinking オフの壁時計を取ったあとが妥当。
