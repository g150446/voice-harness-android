# BLE プロトコル仕様

nRF52840 (`voice-bridge-ble/nrf52-handy` ファームウェア) と Android アプリ間の BLE 通信仕様。

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
| `0x20` | ライトスリープ移行 | なし |
| `0x21` | ライトスリープ復帰 | なし |

**重要**: 録音トリガーは `0x01`/`0x02` のみ。`0x11` (motion_settled) はモーション状態の通知であり、録音ジェスチャーではない。

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

電話マイクボタン用の手動制御コマンド（BLE ジェスチャー録音では不使用）。

| バイト | 意味 |
|---|---|
| `0x01` | 録音開始コマンド |
| `0x00` | 録音停止コマンド |

## 再接続ロジック

切断・スキャン失敗時は指数バックオフで自動再接続。

| 試行 | 待機時間 |
|---|---|
| 1回目 | 2 秒 |
| 2回目 | 4 秒 |
| 3回目 | 8 秒 |
| … | … |
| 上限 | 60 秒 |

スキャンタイムアウト: 30 秒（`SCAN_TIMEOUT_MS`）
