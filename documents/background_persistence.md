# バックグラウンド持続性

画面消灯中（特に長時間）でもアプリが動作し続けるための仕組みをまとめる。

## 問題

Android の **Doze モード**は画面消灯が続くほど段階的に制限を強化する。
深い Doze 状態になると `PARTIAL_WAKE_LOCK` は無視され、フォアグラウンドサービスでさえ
Samsung・Xiaomi などの OEM 端末では停止させられることがある。

短時間の消灯では動作するのに長時間後に止まるのは、この Doze の段階的強化が原因。

## 対策の全体像

| 施策 | 効果 | 実装箇所 |
|---|---|---|
| `ForegroundService` + 通知 | OS に強制終了されにくくする | `BleConnectionService` |
| `START_STICKY` | 強制終了されても OS が自動再起動 | `BleConnectionService.onStartCommand` |
| `android:stopWithTask="false"` | タスクスワイプでもサービスを維持 | `AndroidManifest.xml` |
| `onTaskRemoved` 再起動 | タスク削除時に明示的に自身を再起動（stopWithTask の補完） | `BleConnectionService.onTaskRemoved` |
| `PARTIAL_WAKE_LOCK` | BLE 接続中は CPU をスリープさせない（軽 Doze まで有効） | `BleConnectionService` |
| バッテリー最適化除外 | OEM 独自の省電力によるサービス停止を防ぐ | `MainActivity`（初回起動時に要求） |
| **ウォッチドッグアラーム** | 深い Doze でも定期的に CPU を起こしてサービスを確認・再起動 | `ServiceWatchdog` / `WatchdogReceiver` |
| **WorkManager 定期ワーク** | JobScheduler ベースの補完ウォッチドッグ（OEM が好意的に扱う） | `WatchdogWorker` |
| **CompanionDeviceManager** | OS がプロセスをコンパニオンデバイスアプリとして保護（OEM 電力管理より上位レイヤー） | `CompanionDevicePresenceService` / `BleConnectionService` / `MainActivity` |
| `BootReceiver` | 端末再起動後にサービスとウォッチドッグを自動起動 | `BootReceiver` |

## ウォッチドッグアラームの仕組み

`PARTIAL_WAKE_LOCK` は深い Doze idle では機能しない。
深い Doze を突破できる正しい手段は `AlarmManager.setExactAndAllowWhileIdle()` で、
これは Doze 中でも CPU を起こして発火することが保証されている。

```
BleConnectionService.onCreate()
    └─ ServiceWatchdog.schedule()
           └─ AlarmManager.setExactAndAllowWhileIdle(+5分後)

〜5分後（深い Doze 中は OS 強制で最低9分）〜

WatchdogReceiver.onReceive()
    ├─ BleConnectionService.start()   ← 生きていれば no-op、死んでいれば再起動
    └─ ServiceWatchdog.schedule()     ← 次のアラームを再スケジュール
```

ウォッチドッグは `onDestroy` でキャンセルしない。サービスが強制終了された後も
次のアラームが発火してサービスを再起動できるようにするためである。

## 関連ファイル

| ファイル | 役割 |
|---|---|
| `ServiceWatchdog.kt` | アラームのスケジュール・キャンセルを行うユーティリティ |
| `WatchdogReceiver.kt` | アラーム受信 → サービス起動 → 次アラーム再スケジュール |
| `BleConnectionService.kt` | `onCreate` でウォッチドッグを開始、`PARTIAL_WAKE_LOCK` の管理 |
| `BootReceiver.kt` | 再起動後にサービスとウォッチドッグを起動 |
| `AndroidManifest.xml` | `WatchdogReceiver` の登録、各種パーミッション |
| `MainActivity.kt` | 初回起動時にバッテリー最適化除外をシステムダイアログで要求 |

## ServiceWatchdog の実装詳細

`SCHEDULE_EXACT_ALARM` パーミッションは要求しない（API 31+ でユーザーによる設定操作が必要になるため）。
代わりに `SecurityException` を捕捉して `setAndAllowWhileIdle`（不正確だが Doze 互換）へフォールバックする。

```kotlin
try {
    am.setExactAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP, triggerAt, pi) // 正確・Doze 貫通
} catch (e: SecurityException) {
    am.setAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP, triggerAt, pi)      // 不正確・Doze 貫通
}
```

バッテリー最適化除外（`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`）が許可されていれば
`setExactAndAllowWhileIdle` はパーミッションなしで動作する。

## Doze モードと各施策の有効範囲

```
画面オフ
  │
  ├─ 軽い Doze（数分）
  │     PARTIAL_WAKE_LOCK ✅  ForegroundService ✅
  │
  ├─ 深い Doze（長時間）
  │     PARTIAL_WAKE_LOCK ❌  ForegroundService ⚠️(OEM 次第)
  │     setExactAndAllowWhileIdle ✅ ← ウォッチドッグがここで機能
  │
  └─ 端末再起動
        BootReceiver ✅ → サービス＋ウォッチドッグを再起動
```

## 制限事項

- 深い Doze 中、OS はアラームの発火間隔を最低 9 分に制限する。
  BLE ジェスチャーから録音開始までの処理はファームウェア（nRF52840）が担うため、
  この遅延は実際の録音動作には影響しない。ウォッチドッグはあくまでサービスの
  生死を確認・回復するためのものである。

- 一部の OEM（特に Huawei）は独自の強力な省電力機能を持ち、上記の対策をすべて
  施しても停止させることがある。その場合はシステム設定の「バッテリー」→「アプリ起動」
  または「保護されたアプリ」でこのアプリを手動で許可する必要がある。
