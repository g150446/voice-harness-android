# BLE プロトコル仕様

nRF52840 (`harness-node/nordic-main` ファームウェア) と Android アプリ間の BLE 通信仕様。

## GATT サービス構成

| 項目 | UUID |
|---|---|
| Service | `00000001-0000-1000-8000-00805f9b34fb` |
| TX Characteristic (nRF→Android, Notify) | `00000002-0000-1000-8000-00805f9b34fb` |
| RX Characteristic (Android→nRF, Write) | `00000003-0000-1000-8000-00805f9b34fb` |
| CCCD | `00002902-0000-1000-8000-00805f9b34fb` |

## 接続シーケンス

```
Android                          nRF52840
   │── scan (no filter) ─────────────▶│
   │◀─ advertisement (Service UUID) ──│
   │── connectGatt ──────────────────▶│
   │── requestConnectionPriority(HIGH)│
   │── requestMtu(247) ───────────────▶│
   │◀─ onMtuChanged ──────────────────│
   │── discoverServices ─────────────▶│
   │◀─ onServicesDiscovered ──────────│
   │── setCharacteristicNotification ─▶│
   │── writeDescriptor(CCCD, ENABLE) ─▶│
   │◀─ onDescriptorWrite ─────────────│
   │         (CONNECTED)              │
```

**スキャンフィルタについて**  
`ScanFilter.setServiceUuid()` は Advertising Data (AD) のみ対象で、Scan Response を見ない。
nRF52840 は Service UUID を Scan Response に入れる場合があるため、フィルタなし (`emptyList()`) でスキャンし、
`onScanResult` 内で `scanRecord?.serviceUuids` を手動チェックしている。

## TX パケット形式（nRF → Android）

### 音声パケット

```
Byte 0       Byte 1   Bytes 2+
seq (0-255)  0xAA     PCM データ (16-bit LE, mono, 16kHz)
```

- `seq` はラップアラウンドするシーケンス番号（欠落検出用）
- PCM は 16-bit Little Endian 符号付き整数
- サンプルレート 16,000 Hz、モノラル
- 通常のPCM payloadは最大200 bytes（パケット全体は最大202 bytes）
- 20 ms / 640 bytesの音声フレームは通常4通知に分割され、約200 notifications/sになる

## 音声ストリームの送達規約

音声通知は「GATT APIを呼び出した」だけで送達済みと扱わない。HarnessNodeは
`bt_gatt_notify_cb()` の完了コールバックを使い、次の規約を守る。

1. 同時送信中の通知を6個までに制限する
2. `-ENOMEM` / `-EAGAIN` の場合は同じPCM範囲を再試行する
3. 通知が受理された後だけ `seq` とPCMオフセットを進める
4. 受理済みPCM通知をdrainしてから録音停止イベント `0x02` を送る

Android側は接続時と録音開始時に `CONNECTION_PRIORITY_HIGH` を要求する。また、PCMと
録音イベントを単一の順序付きChannelで処理し、録音停止が未処理PCMを追い越さないように
する。PCMの期待量は毎秒32,000 bytesで、1秒以上の録音では壁時計時間の70%以上を
受信できなければASRを実行しない。

Bluetoothヘッドセット併用時の音声経路と調査手順は
[`ble_audio_reliability.md`](ble_audio_reliability.md)を参照。

### イベントパケット

```
Byte 0  Byte 1  Byte 2      Bytes 3+
0x00    0x55    event_code  オプションデータ
```

| event_code | 意味 | データ |
|---|---|---|
| `0x01` | **録音開始** (firmware が自律判断) | なし |
| `0x02` | **録音停止** (firmware が自律判断) | なし |
| `0x10` | モーション中 | float32×1 or ×3 (x, y, z 加速度) |
| `0x11` | モーション収束 | float32×3 + uint32 + float32×3 (統計情報) |
| `0x12` | ダブルタップ検出 | なし |
| `0x14` | シングルタップ検出 | なし |
| `0x20` | ライトスリープ移行 | なし |
| `0x21` | ライトスリープ復帰 | なし |
| `0x30` | ジェスチャ診断（ライブ） | stage, reason, f32×3（17 B） |
| `0x33` | ジェスチャ履歴 begin | count, session（5 B）。`GESTURE_DEBUG_HISTORY=1` のみ |
| `0x34` | ジェスチャ履歴 entry | u16 t_ms, stage, reason, f32×3（19 B） |
| `0x35` | ジェスチャ履歴 end | count, session（5 B） |
| `0xD0` | タップ診断（レジスタ値、接続確立時に1回） | read_ret i8 + レジスタ8 B + u16 + i8（15 B） |
| `0xD1` | 生 `TAP_SRC`（タップ1回につき1件） | TAP_SRC u8 + read_ret i8（5 B） |
| `0xD2` | IMUレジスタ応答（RX `0x50`/`0x51`） | reg, value, ret（6 B） |

`0xD0`〜`0xD2` はファーム側のタップ経路診断用（FW `0.0.82+`）。Androidはこれらを
パースせず、`BleManager` の hex ダンプログにのみ現れる。`0xD0` は `0.0.87` まで
2秒周期で流れていたが、`0.0.88` で**接続確立時の1回だけ**になった。任意のタイミングで
レジスタを見たいときは RX `0x51`（個別読み出し）→ `0xD2` を使う。

**重要**: 録音トリガーは `0x01`/`0x02` のみ。`0x11` (motion_settled) と
`0x12` (double_tap) は通知イベント。通常モードでは従来どおり通知のみ、運転モードではNode側がダブルタップで録音開始/終了をトグルするため、Androidはこのイベントを受けて録音状態を変更しない。

## 運転モード

AndroidはRXへ `[0x05, 0x00]`（通常）または `[0x05, 0x01]`（運転）を送る。録音中の切替はNodeが現在の録音終了後に適用する。NodeはTXへ `[0x00, 0x55, 0x40, effective, pending]` を返す。運転モードではジェスチャー検出を停止し、ダブルタップのみで録音を開始・終了する。

`0x40` の扱い（FW `0.0.88+`、Android対応済み）:

- Nodeは**要求を受理した時点**と**実際に適用した時点**の2回 ack を送る。受理時のackが
  ないと、録音中に保留された切替をAndroidが観測できない（`0.0.87` までは適用時にしか
  送らず、`pending` が立った ack は構造上届かなかった）。
- ack は `notify_all_conns()` で**全接続へ**飛ぶ。`ConnectionPriority.MAC_HANDY` で
  Mac Handy に primary を譲っている間も Android がモードを追える（`0.0.87` までは
  primary 1本にしか飛ばず、secondary の Android には届かなかった）。
- 接続確立時にも primary / secondary のどちらでも現在のモードを1回送る。
- `pending == 0xff` は保留なし。`effective` / `pending` はいずれも 0=通常、1=運転。

Android側は `BleManager.parseOperationModeAck()` でパースし、`BleConnectionService` の
`nodeDrivingMode` / `nodePendingDrivingMode` に反映する。既存の `drivingMode` は
`DrivingModeController` の判定と手動overrideによる**アプリ側の意図**なので別物として残す。
ホーム画面では保留中に「運転へ切替（録音終了後）」「通常へ切替（録音終了後）」、
意図とNodeの実効モードが食い違う間は「Node: 運転」「Node: 通常」を警告色で併記する。
未確認・一致時は表示を変えない。切断時は両方 `null`（未確認）へ戻し、古い値を
Nodeの現在値として見せない。

`0x12` と `0x14` は XIAO nRF52840 Sense の LSM6DS3TR-C 内蔵タップ検出を使用する。
IMU の INT1 出力を nRF52840 の GPIO 割り込みで受けたファームウェアが、このBLEイベントを
送信する。Androidはダブルタップの受信回数と最終受信時刻をホーム画面へ表示する。
シングルタップは診断ログへ記録するだけで、録音・Kindleページ送り・グラス表示は変更しない。

FW `0.0.87+` の判定手順は次のとおり（Android側で意識すべき点は最後の2行）。

1. 1打目の衝撃でIMUがINT1をassertする（single割り込みも INT1 に割り当てているため）。
2. ファームウェアは **`TAP_SRC` に触れずに350ms待つ**。ここで読むとLIRラッチが解除され、
   進行中のダブルタップ判定がハードウェア側で中断されてしまう。
3. HWのDuration窓（約308ms）が閉じた後に `TAP_SRC` を1回だけ読む。
   `TAP_IA|DOUBLE|Z` なら `0x12`、それ以外なら `0x14`。
4. クールダウンは700ms。singleとdoubleで別々に持つ（FW `0.0.81`〜）。

したがって **タップ1回につきAndroidが受け取るイベントはちょうど1つ**で、
`0x12` の直前に `0x14` が来ることはない。物理タップからBLE通知までは約350〜400ms遅れる。

**FW `0.0.76`〜`0.0.86` の回帰**: `0.0.76` が single 割り込みを INT1 に追加した結果、
ファームウェアが1打目の時点で `TAP_SRC` を読むようになり、上記2のラッチ解除で
ダブルタップ判定が毎回壊れていた。ダブルタップは `TAP_SRC=0x21`（`SINGLE|Z`、
`TAP_IA` なし）2発に分解され、`0x12` はAndroidへ一切届かなくなっていた。
`0.0.81` で修正した「single/doubleのクールダウン共有」も実バグだが、こちらが本体。

### 0x30 ジェスチャ診断

```
17 bytes: [0x00][0x55][0x30][stage][reason][f32 v1][f32 v2][f32 v3]
```

stage 例（FW 0.0.68）: `0x01` outbound_start, `0x02` outbound_ready,
`0x07/08` hold start/ready, `0x09` match, `0x0A` match_detail,
`0x0C` stop_palm_up, `0x0D/0E` gyro on/off, `0x0F` outbound_gyro,
`0x22` hold_sample, `0x23` motion_complete, `0x24` palm_down_gate, `0x80` reset。
`match_detail` v1=xy, v2=lift入口+imp, v3=roll_at_lift。reason bit0=before_flip, bit1=xy_waive。
詳細は harness-node `docs/flex_pronation_gesture.md`。

**Android 側の扱い**

- `BleManager` が `0x30` をパースし `GestureDiagStore` に蓄積する（音声パイプラインには載せない）
- 録音停止時に時間窓をスライスし、`HistoryEntry.gestureDiags` に保存（最新 `outbound_start` 起点・マイルストーン優先・最大60件）
- 履歴詳細で文字起こしと並べて表示（ライブ確認はホーム「ジェスチャ診断」）
- 本番 FW はライブ `0x30` のみで足りる（バッチ不要）

### 0x33–0x35 ジェスチャ履歴バッチ

録音終了（`0x02` の前後）またはシーケンス失敗後にまとめて送信。録音中の PCM とは競合しない。
`GESTURE_DEBUG_HISTORY=1` のデバッグ OTA のみ。

```
5 bytes:  [0x00][0x55][0x33][count][session]
19 bytes: [0x00][0x55][0x34][u16 t_ms LE][stage][reason][f32×3]
5 bytes:  [0x00][0x55][0x35][count][session]
```

Android は `GestureDiagStore` に蓄積する。停止直後にバッチが届けば履歴スナップショットとして優先利用する。

### 0x10 モーション中パケット詳細

```
15 bytes: [0x00][0x55][0x10][f32 x][f32 y][f32 z]
 7 bytes: [0x00][0x55][0x10][f32 z]
 3 bytes: [0x00][0x55][0x10]
```

### 0x11 モーション収束パケット詳細

```
31 bytes: [0x00][0x55][0x11][f32 x][f32 y][f32 z][u32 elapsed_ms][f32 avg_speed][f32 peak_speed][f32 distance]
23 bytes: [0x00][0x55][0x11][f32 z][u32 elapsed_ms][f32 avg_speed][f32 peak_speed][f32 distance]
```

## RX コマンド（Android → nRF）

現行アプリでは録音開始・停止ともファームウェア自律制御（TX `0x01` / `0x02`）で行う。  
Android は録音中に RX `0x00` を送らない（無音自動停止は廃止）。

| バイト | 意味 |
|---|---|
| `0x01` | 録音開始コマンド（アプリは未使用） |
| `0x00` | 録音停止コマンド（アプリは未使用。プロトコル互換で定義のみ） |

## 再接続ロジック

### 通常時

保存済みの優先デバイスがあり、自動再接続が有効な場合は、起動時および予期しない切断時に指数バックオフで自動再接続する。

| 試行 | 待機時間 |
|---|---|
| 1回目 | 2 秒 |
| 2回目 | 4 秒 |
| 3回目 | 8 秒 |
| … | … |
| 上限 | 60 秒 |

スキャンタイムアウト: 30 秒（`SCAN_TIMEOUT_MS`）

### 手動切断時

ユーザーがアプリの `Disconnect` ボタンを押した場合は、サービスは起動したまま `DISCONNECTED` で待機し、即時の自動再接続は行わない。  
この状態ではユーザーが `Scan devices` を押し、スキャン結果の単一選択リストから対象デバイスを選んで `Connect` を押したときだけ再接続を開始する。

### 手動再接続後

ユーザーが手動で再接続に成功したデバイスは再び優先デバイスとして保存され、次回アプリ起動時にはそのデバイスへ自動再接続できる。
