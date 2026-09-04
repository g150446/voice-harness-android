# ジェスチャー検出・診断・運転判定の既定 OFF（0.0.95）

2026-09-04。日常利用での誤認識を抑えるため、手首ジェスチャー録音・IMU 診断送信・
運転モード自動判定を **既定オフ** にし、必要なときだけ ON にできるようにした。

関連:

- プロトコル: [`ble_protocol.md`](ble_protocol.md)（RX `0x07` / TX `0x3A`）
- 誤発火経緯: [`gesture_false_trigger.md`](gesture_false_trigger.md)
- Node FW: `harness-node` `0.0.95`（`docs/ota_update_notes.md`）
- Node 運用: `harness-node/docs/nordic_main_guide.md`

---

## 既定値（まとめ）

| 機能 | 既定 | 有効化 | BLE |
|------|------|--------|-----|
| 手首ジェスチャー録音 | **OFF**（タップのみ） | ホーム「ジェスチャー録音」 | RX `[0x07, e]` → TX `0x3A` |
| IMU 軌跡収集（6 軸 dump） | **OFF**（従来どおり） | ホーム「ジェスチャーIMU収集」 | RX `[0x06, e]` → TX `0x39` |
| ライブ診断 `0x30` | ジェスチャー OFF なら出ない | ジェスチャー ON 時に連動 | （検出 SM 連動） |
| 運転モード自動判定（AR） | **OFF**（強制 NORMAL） | 通知「運転判定」で auto | RX `[0x05, m]` → TX `0x40` |
| シングルタップ録音 | **ON**（ホスト承認） | 常時 | TX `0x14` → RX `0x01`/`0x00` |

**運転モード（0x05）とジェスチャー検出（0x07）は独立。** 運転中は検出 ON でもジェスチャー停止。

---

## なぜ独立スイッチか

従来は「通常モード = ジェスチャー常時 ON」「運転モード = タップのみ」だけだった。
日常の誤発火対策としてタップのみにしたい場合、運転モードを流用すると:

- UI / 通知が「運転」と誤表示される
- Activity Recognition と意味が混ざる
- 未接続時の Node 単体ではジェスチャーが残る

そのため FW `0.0.95` で **`CMD_SET_GESTURE_DETECT`（0x07）** を新設した。

---

## Node（FW `0.0.95`）

- `static bool gesture_detect_enabled` — BSS 既定 **false**（未接続でもジェスチャー OFF）
- RAM のみ。リセットで OFF。接続 greeting で `0x3A` を送る
- `process_gesture_sample()`: detect OFF または DRIVING なら SM を進めない
- 録音中のジェスチャー停止も detect ON かつ NORMAL のときだけ
- 旧アプリ（0x07 を送らない）: Node は既定 OFF のまま → タップのみ

OTA 実績（2026-09-04）:

- 253887 B / 51.9 s
- slot 0 `active` + `confirmed` / version `0.0.95`

---

## Android

| 部品 | 役割 |
|------|------|
| `GestureDetectPreferences` | prefs `gesture_detect` / `enabled` 既定 false |
| `BleConnectionService` | StateFlow・接続時 `0x07` 再送・`0x3A` ack |
| `BleManager` | `parseGestureDetectAck`（0x3A） |
| `MainActivity` | Switch「ジェスチャー録音」+ ステータス文言 |
| `DrivingModeController` | `override` 既定 **0**（NORMAL 固定、AR しない）。auto は `setOverride(null)` |

接続時の RX 再送順（`sendToRx` 直列化）:

1. 運転モード `0x05`
2. IMU 収集 `0x06`
3. ジェスチャー検出 `0x07`

---

## 利用手順

### 日常（既定）

1. Node `0.0.95+` + 本アプリ
2. シングルタップで録音 start/stop
3. ジェスチャーは発火しない

### ジェスチャー開発・収集時

1. ホーム「ジェスチャー録音」→ ON（Node が `0x3A` で ON を返すこと）
2. 必要なら「ジェスチャーIMU収集」→ ON
3. 終わったら両方 OFF に戻す

### 運転モード

- 手動: 通知「通常」/「運転」
- 自動: 通知「運転判定」（Activity Recognition を opt-in）

---

## 互換性

| 組み合わせ | 結果 |
|------------|------|
| 新アプリ + FW 0.0.95 | 完全対応。既定タップのみ |
| 新アプリ + 旧 FW（≤0.0.94） | `0x07` 無視・`0x3A` なし → UI「Node未確認」。タップは動作。ジェスチャーは旧 FW のまま常時 ON の可能性 |
| 旧アプリ + FW 0.0.95 | 0x07 未送信 → Node 既定 OFF（タップのみ）。ジェスチャーは使えない |

ジェスチャーを使うには **アプリ + Node 0.0.95 の両方** が必要。
