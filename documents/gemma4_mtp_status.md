# Gemma 4 E2B MTP 有効化状況

> 調査日: 2026-08-17 / 有効化: 2026-08-17 / 設定画面トグル: 2026-08-18
> 対象: Voice Harness Android / Gemma 4 E2B / LiteRT-LM 0.14.0

## 結論

Gemma プロファイルでは、Gemma 4 E2B の MTP（Multi-Token Prediction / speculative
decoding）を**有効化済み**。エンジン生成時にモデルの MTP 対応を判定し、対応していれば
自動的に有効になる。設定画面から ON/OFF を切り替えられる（既定 ON）。

| 項目 | 状態 |
|---|---|
| Gemma 4 E2BモデルのMTP対応 | 対応済み |
| LiteRT-LM 0.14.0のMTP対応 | 対応済み |
| 現行アプリからのMTP有効化 | 有効（Gemmaのみ、capability判定付き） |
| 設定画面での切替 | 可（既定ON、Gemmaプロファイル時のみ表示） |
| Qwenプロファイル | 無効（既定のまま） |

## 設定画面からの切替

モデル設定画面の「MTP / 投機的デコード（Gemma）」トグルで切り替える。

- 保存先: `model_prefs` の `speculative_decoding`（既定 `true`）
- `ModelManager.isSpeculativeDecodingEnabled()` / `setSpeculativeDecodingEnabled()`
- `GemmaOnDeviceBackend.ensureReady()` がこの値で `AUTO` / `DISABLED` を選ぶ

MTP はエンジン生成時にしか効かないため、トグル操作で即座にモデルを再ロードする。
再ロードは `BleConnectionService.reloadOnDeviceBackend()` → 既存のプロファイル切替経路
（`OnDeviceAiFacade.switchProfile()` が現行バックエンドを無条件に release し、
`VoiceProcessor` が背景で暖機）を再利用しており、専用の配線は持たない。

### 実態の表示

トグルが ON でも、モデル未対応や初期化失敗の退避で実際には無効になることがある。
そのため実際にエンジンへ渡った値を別に表示する。

- `ModelStatus.speculativeDecodingActive`
- `LitertLlmSupport.createEngine()` が `engine.initialize()` 成功時に
  `ModelManager.recordSpeculativeDecoding(useMtp)` で記録
- 設定画面の `Decode: N tok/s` の下に `MTP: 有効 / 無効` として表示

Qwen のロードでも `false` で上書きされるので、プロファイル切替後に Gemma の値が残らない。

## 実装

`LitertLlmSupport.createEngine()` に `speculativeDecoding: SpeculativeDecodingMode` を追加した。

```kotlin
internal enum class SpeculativeDecodingMode { AUTO, DISABLED }
```

- `AUTO` … モデルが対応していれば有効化。`GemmaOnDeviceBackend.ensureReady()` が
  設定トグルONのときに指定する
- `DISABLED` … 既定値。設定トグルOFFの Gemma と、`DedicatedChatEngine`（Qwen系）

処理の流れ:

```
createEngine(speculativeDecoding = AUTO)
  ↓
Capabilities(modelPath).use { it.hasSpeculativeDecodingSupport() }
  ↓ true
GPU + MTP → 失敗したら CPU + MTP → それも失敗したら CPU (MTP off)
```

### 守っている前提

1. **グローバルフラグの漏れ防止** — `ExperimentalFlags.enableSpeculativeDecoding` はプロセス
   全体の実験的設定なので、`Engine(config)` の直前で毎回 `true` / `false` を明示代入する。
   有効化するときだけ書く実装にはしない。これにより、Gemma をロードした後に Qwen を
   ロードしても `true` が引き継がれない
2. **非対応モデルへの強制を避ける** — drafter を持たないモデルに `true` を渡すとエラーに
   なるため、`Capabilities` で判定してからフラグを立てる。判定自体が失敗した場合も
   `false` に倒す
3. **MTP 起因の初期化失敗でアプリを壊さない** — backend フォールバック（GPU→CPU）を尽くしても
   初期化できなかった場合、MTP を切って最後にもう一度初期化する。
   ログ: `MTP initialization failed for <path>; retrying without MTP`

### opt-in

`ExperimentalFlags` はクラスレベルで `@ExperimentalApi`（`@RequiresOptIn(level = ERROR)`）が
付いているため、`createEngine` に `@OptIn(ExperimentalApi::class)` を付けている。
`Capabilities` は opt-in 不要。

なお `ExperimentalFlags` / `Capabilities` は Kotlin metadata を持つので、
`Conversation.getBenchmarkInfo()` と違いリフレクションは不要でそのまま呼べる。

### 確認ログ

```bash
adb logcat -s LitertLlmSupport:D GemmaOnDeviceBackend:D
```

```text
Engine initialized backend=GPU mtp=true /data/.../models/gemma-4-E2B-it.litertlm
```

`mtp=false` なら有効化されていない。効果は設定画面の `Decode: N tok/s` で比較する。

## 調査時の裏付け

### モデル

`models/gemma-4-E2B-it.litertlm` のバイナリ内に次の MTP 関連セクション・識別子を確認した。

- `tf_lite_mtp_drafter`
- `per_layer_embedding`

調査時のモデルSHA-256:

```text
181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c
```

### LiteRT-LMランタイム

`com.google.ai.edge.litertlm:litertlm-android:0.14.0` の AAR に含まれる API（javap で確認）:

- `ExperimentalFlags.enableSpeculativeDecoding: Boolean?`（初期値 `null` = モデル/エンジンの既定）
- `Capabilities(modelPath: String) : java.lang.AutoCloseable`
- `Capabilities.hasSpeculativeDecodingSupport(): Boolean`
- `nativeHasSpeculativeDecodingSupport`、MTP drafter および speculative decoding 処理

### 関連コード

- `app/src/main/java/com/g150446/voiceharness/LitertLlmSupport.kt`
- `app/src/main/java/com/g150446/voiceharness/GemmaOnDeviceBackend.kt`
- `app/src/main/java/com/g150446/voiceharness/ModelManager.kt`（prefs / `speculativeDecodingActive`）
- `app/src/main/java/com/g150446/voiceharness/GroqSettingsActivity.kt`（トグルと表示）
- `app/src/main/java/com/g150446/voiceharness/BleConnectionService.kt`（`reloadOnDeviceBackend`）
- `app/build.gradle.kts`

## 参考資料

- [LiteRT-LM `ExperimentalFlags.kt`](https://chromium.googlesource.com/external/github.com/google-ai-edge/LiteRT-LM/+/f35fcbc35110fe90759baa18abad7bb3894e6c36/kotlin/java/com/google/ai/edge/litertlm/ExperimentalFlags.kt)
- [Gemma 4: Multi-Token Prediction概要](https://ai.google.dev/gemma/docs/mtp/overview)
- [Gemma 4 E2B LiteRT-LMモデル](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm)
- [Gemma 4 E2B LiteRT-LM MTP利用例](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/blame/main/notebook.ipynb)
