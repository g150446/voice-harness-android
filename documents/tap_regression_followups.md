# タップ回帰調査の申し送り（2026-08-31）

FW `0.0.76`〜`0.0.86` でダブルタップ `0x12` が Android に届かなくなっていた不具合は
FW `0.0.87` で解消し、実機で single 5/5・double 5/5・誤検出 0（10/10 PASS）を確認した。
経緯と最終仕様は [`ble_protocol.md`](ble_protocol.md) の「0x12 / 0x14」節、
およびファーム側 `harness-node/docs/nordic_main_guide.md` の
「シングル／ダブルタップ」を参照。

この調査で入れたまま、あるいは直さずに残した項目が 2 件ある。いずれも現状の動作は
壊れていないので急ぎではないが、次にこの周辺へ触るときに一緒に片付けたい。

---

## 1. `0xD0` タップ診断が 2 秒周期で流れ続けている

**現状**

ファーム側 `TAP_DIAG_INTERVAL_MS = 2000`（`harness-node/nordic-main/src/main.c`）で、
タップ関連レジスタのスナップショット `0xD0`（15 バイト）を **全接続へ 2 秒ごとに
notify し続けている**。真因特定のために入れた計装で、`0.0.87` にもそのまま入っている。

**影響**

- 帯域としては 15 B / 2 s なので実質無視できる。
- ただし `BleManager` は高レート系（`0x10` / `0x30` / `0x34`）以外の TX イベントを
  すべて hex ダンプするため、**logcat に 2 秒ごとに `TX event 0xD0 (...)` が出続ける**。
  他のイベントを追うときに邪魔になる。
- Node 側も 2 秒ごとに I2C を 8 回叩いている。

**対応案**

調査が一段落したので、常時配信をやめるのが妥当。いずれかで足りる。

- `TAP_DIAG_INTERVAL_MS` を 30000 程度へ延ばす（最小の変更）
- 定期送信をやめ、RX `0x51`（レジスタ読み出し）で要求されたときだけ返す
- 接続直後に 1 回だけ送る

`0xD1`（タップ 1 回につき生 `TAP_SRC` 1 パケット）は頻度が低く、閾値や軸の調整時に
そのまま効くので残してよい。

**変更先**: `harness-node/nordic-main/src/main.c`（`TAP_DIAG_INTERVAL_MS` と
メインループの `send_tap_diag()` 呼び出し）

---

## 2. `0x40`（運転モード ack）を Android が解釈していない

**現状**

Android は RX へ `[0x05, mode]` を書いて運転モードを切り替え、Node は TX へ
`[0x00, 0x55, 0x40, effective, pending]` で応答する（[`ble_protocol.md`](ble_protocol.md)）。
しかし `BleManager.kt` の `parseSimpleBleEvent` に `0x40` の分岐がなく、
**`else -> null` で捨てられている**。hex ダンプログには出るが、それだけ。

**影響**

- **Node が実際に適用したモードをアプリが確認できない。** 送りっぱなしで、
  `BleConnectionService.drivingMode` が持っているのは `DrivingModeController` の
  判定と `setDrivingMode()` での楽観的更新、つまりアプリ側の意図だけ。
- 録音中にモードを切り替えた場合、Node は `pending` として保持し録音終了後に適用する。
  この「まだ切り替わっていない」状態が UI に出ないので、ユーザーからは
  切替が効いていないように見える。
- BLE 再接続時などに Node 側とアプリ側のモードが食い違っても検知できない。

**対応案**

1. `BleManager.kt` に `0x40` の分岐を追加し、`BleEvent` へ
   `DrivingModeAck(effective: Int, pending: Int)` を足す（`pending == 0xff` は
   保留なし）。ペイロードは 5 バイト固定。
2. `BleConnectionService` に **Node が適用中のモード**を持つ StateFlow を新設し、
   ack 受信で更新する。既存の `_drivingMode` は
   `DrivingModeController` の判定と手動 override、すなわち**アプリ側の意図**を
   表しているので、そのまま残して役割を分ける。
3. 両者が食い違っている間、および `pending` が立っている間は UI に
   「録音終了後に切替」を出す。
4. `BleDoubleTapTest` と同じ形でパーサのユニットテストを足す。

**変更先**: `app/src/main/java/com/g150446/voiceharness/BleManager.kt`、
`BleConnectionService.kt`、`VoiceViewModel.kt` / `MainActivity.kt`（表示）
