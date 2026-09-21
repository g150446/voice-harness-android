# 折りたたみ端末の外側ディスプレイでKindleがめくれない不具合（2026-09-21、修正済み）

motorola razr 50s を折りたたみ、外側ディスプレイで Kindle を開いてリーダーモードを使うと、
G2 でシングルタップしても次ページが表示されなかった。同じ調査で、自動リーダー開始が
Kindle 前面化のたびにスキップされる状態も見つかった（こちらは原因未特定、下記）。

## 1. 外側ディスプレイでページ送りが効かない（原因特定・修正済み）

### 症状
G2 のシングルタップ → `/api/v1/reading/advance` → `turnKindlePages` が
「Kindleのページ操作または画面更新を確認できませんでした」で失敗する。ログには
`Kindle page turn swipe candidates=[SWIPE_RIGHT]` の後、この警告だけが出る。

### 原因
ページ送りは「アクセシビリティのスクロール → だめなら画面スワイプ」の順で試す。スワイプ
（`KindlePageTurnController.performSwipe`）が次の 2 点を固定していた。

- 座標を `resources.displayMetrics`（既定ディスプレイ = display 0）の寸法で作る
- `dispatchGesture` の送信先ディスプレイを指定しない（= display 0）

折りたたむと Kindle は外側ディスプレイ（display 1、実機で 1056×1066）にあり、display 0
（1080×2640）は消灯している。スワイプは Kindle のいない画面に送られ、y 座標も外側の高さを
超えていた。それでも `onCompleted` が返るので `DISPATCHED` 扱いになり、画面が変わらない
ことでだけ失敗が分かる。

確認に使った情報: `dumpsys window displays` で `mCurrentFocus` が display 1 の Kindle、
`dumpsys activity activities` で `topResumedActivity` が Display #1。

### 修正
`kindleSwipeTarget()`（API 30 以上）が `windowsOnAllDisplays` から Kindle のウィンドウを探し、
そのディスプレイ ID と `getBoundsInScreen` の範囲を取る。`GestureDescription.Builder.setDisplayId`
でそのディスプレイへ送り、座標はウィンドウ範囲の 18% / 82%、縦は中央にする
（`pageTurnSwipeLine`）。API 30 未満、または Kindle のウィンドウが取れないときは従来どおり
既定ディスプレイの全画面寸法を使う。

`accessibility_service_config.xml` の `flagRetrieveInteractiveWindows` と
`canPerformGestures` は元から有効なので設定変更は不要。

**変更先**: `KindlePageTurnController.kt`。テストは `KindleSwipeTargetTest`
（外側ディスプレイの範囲内に収まる・方向・ウィンドウ原点のオフセット・方向不明）。

### 実機検証（2026-09-21）
Tailscale 経由の adb で導入し、外側ディスプレイの Kindle でスワイプが実行され、
`Headless session shown` による再取得まで進むことを確認した。

## 2. 自動リーダー開始が毎回スキップされる（原因未特定）

### 症状
Kindle 前面化のたびに `Skip capture: ineligible` → `Auto reader-mode capture empty` となり、
G2 に Kindle の本文が出ない。ヘッドレス取得の最後の成功は前日 19:37 で、以降は全て失敗した。

### 判明したこと
`HeadlessScreenCapture.isEligible()` が false を返していた。次の条件のどれかで、ログには
どれか出ていなかった。

| 条件 | 調査時の状態 |
|---|---|
| アシスタント役割を保持 | 保持（`cmd role get-role-holders`） |
| ロック解除・画面点灯 | 満たす |
| 自アプリの UI が表示中でない（`OwnAppUiTracker`） | MainActivity 13 個が全て STOPPED（実際には非表示） |
| アシスタントセッションが残っていない | 不明 |
| 取得処理が動作中でない（`active`） | 不明 |

### 入れた変更
- `isEligible()` が false の理由（`Ineligible: <理由>`）をログに出す。次に再発したら
  `adb logcat | grep Ineligible` でどの条件かが分かる。
- `capture()` の `resetLocked()` を `finally` へ移した。取得中にキャンセルされると `active` が
  true のまま残り、以降の取得が永久に不適格になりうる（可能性のある原因の 1 つ）。
- `OwnAppUiTracker.resumedCount()` を追加（ログ用）。

アプリ再導入（プロセス再起動）後は再現しておらず、`Ineligible` ログも出ていない。
原因は特定できていないため、**再発したら上の理由ログを最初に見ること**。

## 再発時の調べ方

```bash
./scripts/adb-tailscale.sh connect
adb -s 100.102.210.64:5555 logcat -v time --pid=$(adb -s 100.102.210.64:5555 shell pidof com.g150446.voiceharness)
```

- `Ineligible:` … 自動開始が止まっている条件
- `Kindle page turn swipe candidates=` の後に `Kindleのページ操作…` … ページ送りが効いていない
- G2 に渡している内容は、adb forward 越しに `GET /api/v1/reading`（`mode` と `bodyText`）で読める。
  ただし、この GET は G2 クライアントの生存確認も兼ねるため、多用しない。
- `adb logcat -d` は Tailscale 越しだとハングすることがある。ファイルへ落としてから grep する。
