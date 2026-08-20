# On-device Gemma 4 (E2B) 統合メモ

## 概要

STT / LLM を Groq API から **端末内 Gemma 4 E2B (LiteRT-LM)** に置換。
TTS は従来どおり Android `TextToSpeech`。

## 依存

- `com.google.ai.edge.litertlm:litertlm-android:0.14.0`
- Kotlin **2.3.0**（LiteRT-LM 0.14 の metadata 2.3 対応）

## モデル配置

推奨ファイル名: `gemma-4-E2B-it.litertlm`
HF: `litert-community/gemma-4-E2B-it-litert-lm`（約 2.5GB）

```bash
# 例: 端末のアプリ filesDir へ push
adb shell mkdir -p /data/data/com.g150446.voiceharness/files/models
adb push gemma-4-E2B-it.litertlm /data/data/com.g150446.voiceharness/files/models/
```

またはモデル設定画面のファイル選択から取り込む。

探索順:
1. SharedPreferences の明示パス
2. `context.filesDir/models/*.litertlm`
3. `context.getExternalFilesDir(null)/models/*.litertlm`
4. 共有Downloadディレクトリ

## パイプライン

```
BLE PCM → VAD → Gemma ASR (audio) → Gemma Chat (text + set_reminder tool) → TTS
```

デフォルトプロファイルは Gemma。設定画面で Qwen に切り替え可能。
ASR タイムアウトは 120 秒、Chat は 60 秒。タイムアウト時は engine を破棄し次回ロードする。

## エンジンの所有と直列実行

Gemmaプロファイルは、1つの `GemmaOnDeviceBackend` エンジンをASRとChatで共有し、
mutexで直列実行する。Gemma ASRと別の高速Chatエンジンを同時に保持しない。

razr 50sでは、複数のLiteRT-LMエンジンを同時に保持した構成でGPU delegateやメモリ資源が
競合し、以前は動作していたGemma ASRがモデル切り替え後に `ASR error` になる事象を確認
した。高速Chatの並行常駐より、ASRの安定性とピークメモリの抑制を優先する。

将来別のChatモデルを再導入する場合は、少なくとも以下を実機で確認する。

- Gemma ASR実行中に別エンジンをロード・推論しない
- GPU delegateを複数エンジンが同時所有しない
- 連続5回のASR / Chatで初期化失敗、OOM、タイムアウトがない
- プロファイル切り替え時に旧エンジンが確実にcloseされる

## 計測ログ (logcat)

```bash
adb logcat -s VoiceProcessor:D GemmaOnDeviceBackend:D ModelManager:D LitertLlmSupport:D
```

- `Model loaded in N ms`
- `ASR done in N ms`
- `Chat done in N ms`
- `Engine initialized backend=GPU mtp=true ...`（MTP の有効/無効）

UI の Model Settings / ホームの Model 行にも Load / ASR / Chat ms を表示。

## MTP（speculative decoding）

Gemma はエンジン生成時に `Capabilities.hasSpeculativeDecodingSupport()` で判定し、対応して
いれば MTP を自動で有効化する（`SpeculativeDecodingMode.AUTO`）。Qwen 系は既定で無効。

- グローバルな実験的フラグなので、`Engine()` 直前に毎回明示代入してモデル間で漏らさない
- MTP 有効で初期化できない場合、MTP を切って自動リトライする

### 設定画面での切替

Gemma プロファイル時、モデル設定画面に「MTP / 投機的デコード（Gemma）」トグルが出る
（既定 ON）。切り替えるとその場でモデルを再ロードして反映する。

A/B 比較の手順:

1. トグル ON でモデルを読み込み、短文 Chat を1回流して `Decode: N tok/s` を控える
2. トグルを OFF にする（自動で再ロードされる）
3. 同じ短文 Chat をもう一度流し、`Decode: N tok/s` を比べる

`Decode` の下の `MTP: 有効 / 無効` は設定値ではなく**実際にエンジンへ渡った値**。
トグル ON でも、モデル未対応や初期化失敗の退避で `無効` になることがある。

詳細は `documents/gemma4_mtp_status.md`。

## Phase 0 Go/No-Go (razr 50s)

1. Settings → Load model now
2. 録音ジェスチャーで短文日本語
3. 連続 5 回で熱・遅延・クラッシュを確認

目安:
- Load 成功、OOM なし
- ASR 15秒音声でおおよそ 10 秒以内
- 短文 Chat おおよそ 10 秒以内

## ASR 認識語彙

Gemma ASR プロンプトには固有名詞の希望表記リストを付与できる。

- 既定語は `AsrVocabularyCatalog.builtIn`（初期: ちいかわ / ハチワレ / うさぎ）
- 製品として常に載せたい語は `builtIn` に `AsrVocabularyTerm` を1行追加する
- 再ビルドなしの試験語はアプリ files 直下の `asr_vocabulary_extra.txt`  
  （1行1語。`表記` または `表記<TAB>読みヒント<TAB>説明`。`#` はコメント）
- 日本語 / 自動のときだけプロンプトへ載せる。英語モードには載せない

## 会話コンテキストのリセット

マルチターン履歴（`ConversationSession`）は最大約10分、または次の音声指示でクリアできる。

例: 「コンテキストをリセットして」「会話をクリア」「reset context」

- リセットのみ: 固定の確認文を返し、Chat には進まない
- 「リセットして。〇〇は？」のように続く文がある場合: 履歴を消してから後半だけを新規 user として Chat
- 永続の会話履歴 UI（History）は消さない。対象は推論用セッションのみ

## 未対応 / 今後

- モデル自動ダウンロード UI
- GPU バックエンド自動選択
- E4B 切替
- ネットワークAPIフォールバック（方針上なし）
- ASR 語彙の設定画面 UI
