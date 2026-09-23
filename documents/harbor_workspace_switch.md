# 「音声でワークスペースが切り替わらない」の正体（2026-09-23 解決済み）

> 切り替えは**最初から成功していた**。追従していなかったのは Android アプリの画面。
> 現在の仕様は [`harbor_confirm_voice_intent.md`](harbor_confirm_voice_intent.md) と
> [`smart_glasses_output.md`](smart_glasses_output.md) が正。ここは「なぜそうなっているか」を残す。

---

## 1. 履歴が調査を終わらせた

`POST /v1/workspaces/{id}/activate` は成功していた。端末の `voice_history_prefs.xml`
（`history_json`）に、Harbor モード44件のうち切り替え要求が3件あり、**失敗が1件も無い**。

```
17:05:38  ハーネスノードワークスペースに切り替えて   → harness-node に切り替えました
19:25:46  ハーネスノードのワークスペースに切り替えて → harness-node に切り替えました
20:11:01  ターミナルハーバーワークスペースに切り替えて → terminal-harbor に切り替えました
```

決定打は 20:12 の解釈結果。「現在のワークスペースでは Claude Code が起動していません」と
答えている ＝ `fetchInterpretContext` の `firstOrNull { it.selected }` が**移動先**を返していた。
Harbor 側の selected も G2 ミラーも動いていた。

**症状の報告（「切り替わらない」）とログ（「切り替わった」）が食い違うときは、
両方が見ているものが同じか疑う。** この件では違った — ユーザーが見ていたのはアプリの画面。

## 2. 画面は「Harbor の選択」ではなく「自分のナビゲーション状態」を見ていた

`VoiceViewModel._selectedHarborWorkspaceId` に書き込む場所は**1箇所だけ**だった:

```kotlin
fun openHarborWorkspace(id: String) {   // ワークスペース一覧のタップ
    _selectedHarborWorkspaceId.value = id
    …
}
```

音声経路（`HarborMirrorController.submitCommand` → `client.postActivate`）はここを触らない。
ワークスペース詳細画面は `LaunchedEffect(workspaceId, …)` で 1 秒ごとに
`loadWorkspace(workspaceId)` を回すので、**画面は生きているのに永遠に動かない**。
更新が止まるわけではないぶん、壊れているように見えない。

`activateWorkspace`（一覧のタップ）は `mutateAndRefresh` で uiState を作り直していたので、
**手で切り替えたときだけ正しく動いた**。音声と手で経路が分かれていたのが原因。

## 3. `selectedWorkspaceId` は答えになれない

`HarborUiState.selectedWorkspaceId` は既にあったが、これは使えない。
`loadWorkspace` が**ポーリングのたびに自分が読んだ id で上書きする**ため、
この値は「画面が今どこを見ているか」しか表さない。画面を動かす根拠には決してならない。

そこで `activatedWorkspaceId` を別に置いた。**activate が成功したときだけ**書かれる。
タップ経路も音声経路も `noteActivated` を通す。`VoiceViewModel` はこれを購読して
`_selectedHarborWorkspaceId` を合わせる。

**追従は画面遷移ではない。** ホーム画面にいるユーザーを詳細画面に引きずり出さない
（`_currentScreen` は触らない）。詳細画面にいれば `LaunchedEffect(workspaceId)` が
張り直され、端末・タブ・プラン・会話がまとめて移動先のものになる。

## 4. ついでに直した2件（この症状の原因ではない）

調査の途中で見つけた別のバグ。どちらも本物だが、**今回の「切り替わらない」とは無関係**だった。

- **`HarborContextPrompt.systemAppendix` が末尾で切っていた**。本文の先頭が
  `workspace:` / `switchable_workspaces:`、末尾がスクロールバック。`takeLast(12_000)` は
  スクロールバックを残してヘッダを捨てる。`fetchInterpretContext` は 2,000 行 / 64 KiB まで
  積むので、エージェントが動いていればほぼ常にヘッダが消えていた。
  いまは会話本文だけを切る。
  > 既存テストは**長さしか見ていなかった**。切り詰めのテストは
  > 「何が消えたか」ではなく**「何が残るか」**を書く。
- **`needs_clarification` 付きの `switch_workspace` が目的地ごと捨てられていた**。
  clarification 分岐は `workspace` を保存しないので、シングルタップが永久に聞き返しになる
  行き止まりができる。確認画面を持つアプリでは**確認はタップであって、モデルの聞き返しではない**。
  行き先を名指しできている switch は clarification にしない。
- あわせて、clarification の再表示が**前回と同一の文字列**だった。
  [`harbor_confirm_voice_intent.md`](harbor_confirm_voice_intent.md) §2.3 の
  「どの分岐でも必ず G2 に何か出す」では足りない。**「前と違うものを出す」**が要る。

## 5. 実機検証（2026-09-23）

motorola razr 50s + Even G2 で確認済み。ワークスペース詳細画面を開いたまま
「◯◯ワークスペースに切り替えて」→ シングルタップで、端末・タブ・プラン・会話が
まとめて移動先のものになる。

## 6. 調査に使ったもの

- `adb shell run-as com.g150446.voiceharness cat …/shared_prefs/voice_history_prefs.xml`
  — ログに出ない送信内容と結果が全部ある。**logcat より先にこれを見る。**
  リングバッファは既定 256 KiB で数分で流れるが、履歴は残る。
- tailscale 経由の adb は direct 経路が張れていれば 144MB の debug APK を約1分半で入れられた
  （`adb connect 100.x.x.x:5555`、`ADB_INSTALL_TIMEOUT=30`）。
  [`harbor_confirm_voice_intent.md`](harbor_confirm_voice_intent.md) §4 の
  「Tailscale 経由は10分でタイムアウト」は DERP 中継のときの話。
  ただし `logcat -G` を同時に叩くと転送が落ちる（`device offline`）ので、重ねない。
