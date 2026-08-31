# タップ回帰調査の申し送り（2026-08-31、完了）

FW `0.0.76`〜`0.0.86` でダブルタップ `0x12` が Android に届かなくなっていた不具合は
FW `0.0.87` で解消し、実機で single 5/5・double 5/5・誤検出 0（10/10 PASS）を確認した。
経緯と最終仕様は [`ble_protocol.md`](ble_protocol.md) の「0x12 / 0x14」節、
およびファーム側 `harness-node/docs/nordic_main_guide.md` の
「シングル／ダブルタップ」を参照。

その調査で入れたまま、あるいは直さずに残した項目が 2 件あった。**FW `0.0.88` と
Android 側の対応で両方とも解消済み**。以下はその記録で、次にこの周辺を触るときの
背景説明として残す。**OTA と実機検証だけが未了**（末尾の「残作業」を参照）。

| # | 項目 | 対応 |
|---|------|------|
| 1 | `0xD0` タップ診断が 2 秒周期で流れ続けていた | 接続確立時に 1 回だけへ変更 |
| 2 | `0x40`（運転モード ack）を Android が解釈していなかった | パースして UI へ。あわせて FW 側の ack 欠落 2 件も修正 |

---

## 1. `0xD0` タップ診断の常時配信をやめた

### 何が問題だったか

ファーム側 `TAP_DIAG_INTERVAL_MS = 2000` で、タップ関連レジスタのスナップショット
`0xD0`（15 バイト）を全接続へ 2 秒ごとに notify し続けていた。真因特定のために入れた
計装が `0.0.87` にそのまま残っていたもの。

- 帯域としては 15 B / 2 s なので実質無視できる。
- ただし `BleManager` は高レート系（`0x10` / `0x30` / `0x34`）以外の TX イベントを
  すべて hex ダンプするため、**logcat に 2 秒ごとに `TX event 0xD0 (...)` が出続け**、
  他のイベントを追うときに邪魔だった。
- Node 側も 2 秒ごとに I2C を 8 回叩いていた。

### 採った対応（FW `0.0.88`）

`TAP_DIAG_INTERVAL_MS` と `tap_diag_last_ms` を削除し、定期送信をやめた。代わりに
`ble_connected()` が `tap_diag_once_pending` を立て、メインループが接続ごとに 1 回だけ
`send_tap_diag()` を呼ぶ。`send_tap_diag()` は I2C を 8 回同期読みするので、BT
コールバックスレッドから直接は呼ばない（同関数が advertising 再開を
`k_work_schedule` に逃がしているのと同じ理由）。

これで「起動時にレジスタが期待どおりか」の確認は残り、logcat は静かになる。
任意のタイミングで個別レジスタを見たいときは既存の RX `0x51` → `0xD2` を使う
（`mac_client/tap_monitor.py` から叩ける）。`0xD1`（タップ 1 回につき生 `TAP_SRC`
1 パケット）は頻度が低く閾値・軸の調整にそのまま効くので温存した。

**変更先**: `harness-node/nordic-main/src/main.c`

---

## 2. `0x40`（運転モード ack）を Android が解釈するようにした

### 何が問題だったか

Android は RX へ `[0x05, mode]` を書いて運転モードを切り替え、Node は TX へ
`[0x00, 0x55, 0x40, effective, pending]` で応答する。しかし `BleManager.kt` の
`parseSimpleBleEvent` に `0x40` の分岐がなく、`else -> null` で捨てられていた。
hex ダンプログには出るが、それだけ。

- **Node が実際に適用したモードをアプリが確認できない。** 送りっぱなしで、
  `BleConnectionService.drivingMode` が持っているのは `DrivingModeController` の
  判定と `setDrivingMode()` での楽観的更新、つまりアプリ側の意図だけだった。
- 録音中にモードを切り替えると Node は `pending` として保持し録音終了後に適用する。
  この「まだ切り替わっていない」状態が UI に出ないので、ユーザーからは
  切替が効いていないように見えた。
- BLE 再接続時などに Node 側とアプリ側のモードが食い違っても検知できなかった。

### 実装して初めて判明した、FW 側の ack 欠落 2 件

「保留中は UI に出す」を実装しようとして、**ack だけではそれが作れない**ことが分かった。
どちらも「送りっぱなしで気づけない」形の欠陥で、Android 側だけを直しても意味がなかった。

- **録音中に受理したモード要求への ack が一切飛んでいなかった。**
  `audio_rx_write` の `CMD_SET_OPERATION_MODE` は `operation_mode_pending` を
  立てるだけで通知せず、`apply_pending_operation_mode()` は録音中なら早期 return する。
  つまり Android は `pending != 0xff` の ack を**構造的に観測できなかった**。
  → 受理時にも `send_operation_mode_status()` を呼ぶようにした。
- **ack が primary conn にしか飛んでいなかった。** `send_operation_mode_status()` は
  `get_primary_conn()` へ 1 本だけ notify していた。タップ系が `notify_all_conns()` を
  使うのと非対称で、`ConnectionPriority.MAC_HANDY` で Mac Handy に primary を譲っている
  間、Android には ack が届かなかった。→ `notify_all_conns()` 化。あわせて
  `ble_connected()` の secondary 分岐でも現在のモードを 1 回送るようにした
  （それまでは primary 分岐にしか無く、secondary の Android は初期モードを取れなかった）。

**次に運転モード周りを触るときは、ack の送信経路が primary 限定になっていないかを
まず疑うこと。** タップ系は `notify_all_conns()`、モード系は primary 限定、という
非対称が長く残っていた。

### 採った対応（FW `0.0.88` + Android）

- `BleManager.kt`: `BleEvent.OperationModeAck(effective, pending)` と
  `parseOperationModeAck()` を追加。ペイロード付きなので `parseSimpleBleEvent`
  （引数なしイベント専用）には混ぜず、`parseMotionActive` と同じ形の別関数にした。
- `BleConnectionService.kt`: `nodeDrivingMode` / `nodePendingDrivingMode` を新設し、
  ack 受信で更新する。**既存の `drivingMode` はアプリ側の意図を表すのでそのまま残し、
  役割を分けた。** 不一致は `Log.w` に出すだけで自動再送はしない（再接続時の再送は
  CONNECTED ハンドラに既存で、ここで再送すると ping-pong しうる）。
  切断時は両方 `null`（未確認）へ戻し、古い値を Node の現在値として見せない。
- `MainActivity.kt`: 保留中は「運転へ切替（録音終了後）」「通常へ切替（録音終了後）」、
  不一致中は「Node: 運転 / Node: 通常」を警告色で併記。未確認・一致時は従来の表示のまま。
- `BleOperationModeAckTest.kt` を追加（`BleDoubleTapTest` と同じ素の JUnit）。

**変更先**: `app/src/main/java/com/g150446/voiceharness/BleManager.kt`、
`BleConnectionService.kt`、`VoiceViewModel.kt`、`MainActivity.kt`、
`harness-node/nordic-main/src/main.c`

---

## 残作業: OTA と実機検証

ユニットテスト（29 スイート）と `:app:assembleDebug`、および FW の
`--sysbuild` クリーンビルドは通過済み。`nordic-main/ota_update.bin` は `0.0.88`
（251064 B、OTA slot 上限 335872 B 以内）で生成済み。**OTA 配信と以下の実機確認が未了。**

1. `adb logcat -s BleManager` を流したまま接続 → `TX event 0xD0` が**接続直後の
   1 回だけ**で、以後 2 秒周期で出ないこと。
2. 非録音中にモード切替 → `TX event 0x40 (00 55 40 01 FF)` が届き、UI の表示が
   Node と一致すること。
3. **録音中に**モード切替 → `pending` が立った ack が届き、UI に
   「運転へ切替（録音終了後）」が出る。録音終了後に 2 通目の ack で表示が消え、
   実効モードが切り替わること。
4. Mac Handy を先に繋いで Android を secondary にした状態で 2 と 3 を再実行 →
   `notify_all_conns` 化により ack が Android にも届くこと。
5. タップ回帰の非退行: single / double を各 5 回 → `0x14` / `0x12` がそれぞれ 5/5、
   誤検出 0。`0xD1` が 1 打につき 1 件出ること。
6. `mac_client/tap_monitor.py` で RX `0x51` を叩き `0xD2` が返ること
   （定期 `0xD0` を止めてもレジスタ確認手段が残っていること）。

OTA 手順は `harness-node/docs/ota_update_notes.md` を参照。
