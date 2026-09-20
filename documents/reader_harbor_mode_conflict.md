# リーダーモードがHarborモードに引き戻される不具合（2026-09-20、修正済み）

Kindleの本文がG2グラスに反映されなくなる、という報告から特定した回帰。
`syncInteractionModeWithG2Client()`（Harborモード追加のcommit `52bc3cb`
「feat: drive Terminal Harbor from Even G2 with one interpreter」で新設）が、
ホーム画面の独立した「リーダーモード」スイッチと衝突していた。

## 何が問題だったか

ホーム画面には操作モードに関わる独立したUIが2種類ある。

- 「操作モード」ボタン（AI対話 / リーダー / Harbor）:
  `setInteractionMode()` を経由するため、`InteractionModePreferences` の保存値も
  正しく更新される。
- 「リーダーモード」スイッチ（単独）:
  `setReadingPassthroughEnabled()` を直接呼ぶだけで、`InteractionModePreferences`
  も `_interactionMode`（ライブ状態）も更新しない。

`BleConnectionService.kt` の500msポーリングループ（`syncInteractionModeWithG2Client()`）は、
「保存済みモードがHarborなら復元する」というロジックを持っていた:

```kotlin
if (saved == InteractionMode.HARBOR &&
    _interactionMode.value != InteractionMode.HARBOR &&
    _harborConnectionState.value.paired
) {
    setInteractionMode(context, InteractionMode.HARBOR)
}
```

保存済みモードが以前のHarbor利用で `HARBOR` のまま残っている状態で「リーダーモード」
スイッチだけをONにすると、ライブ状態は `_interactionMode.value != HARBOR` のままなので
この条件が毎ポーリング成立し、`setInteractionMode(HARBOR)` が呼ばれ続ける。その
`HARBOR` 分岐は `setReadingPassthroughEnabled(context, false, ...)` を呼ぶため、
ONにしたはずのリーダーモードが即座にOFFへ戻される。Kindle側の本文取得自体は動いて
いても、G2への反映が始まる前に無効化されるため「反映されなくなった」ように見える。

## 修正

`syncInteractionModeWithG2Client()` の復元条件に `!_readingPassthroughEnabled.value`
を追加し、リーダーモードが有効な間はHarborモードへの自動復元を止めた。

**変更先**: `app/src/main/java/com/g150446/voiceharness/BleConnectionService.kt`
（`syncInteractionModeWithG2Client()`）

根本的には「操作モードの状態」が `InteractionModePreferences`（永続）、
`_interactionMode`（ライブ）、`_readingPassthroughEnabled`（リーダー単独フラグ）の
3箇所に分散していて食い違いうる設計になっている。今回は最小差分で復元条件に
ガードを足すだけに留めたが、次にこの周辺を触るときはこの3つの整合性を疑うこと。

## 実機検証の結果（2026-09-20）

`./gradlew :app:compileDebugKotlin` / `:app:testDebugUnitTest` は通過。ワイヤレス
adb（`scripts/adb-wireless.sh`）でmotorola razr 50sへ導入し、Kindle→G2のリーダー
モードを実際に操作して本文が反映されることを確認した。
