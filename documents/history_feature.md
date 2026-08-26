# 履歴機能

## 概要

ホーム画面下部に「履歴」ボタンを追加し、過去の録音セッションを一覧・詳細表示できる機能。VAD（Voice Activity Detection）で無音と判定された録音も含めてすべて記録される。

ジェスチャで録音が始まったセッションでは、ファームウェアから届いた **ジェスチャ判定の実測値**（必要に応じて閾値注釈）も同じ履歴に保存し、詳細画面で文字起こしと並べて確認できる。

## 追加・変更ファイル

| ファイル | 種別 | 内容 |
|---------|------|------|
| `HistoryEntry.kt` | 変更 | `gestureDiags` フィールド |
| `HistoryRepository.kt` | 変更 | gestureDiags の JSON 永続化（後方互換） |
| `GestureDiagStore.kt` | 変更 | 録音区間スナップショット / 表示用行 |
| `VoiceProcessor.kt` | 変更 | 録音 start/stop で diag をキャプチャし履歴へ |
| `MainActivity.kt` | 変更 | 履歴一覧バッジ・詳細のジェスチャ判定セクション |

## データモデル

```kotlin
data class HistoryEntry(
    val id: String,           // UUID
    val timestamp: Long,      // System.currentTimeMillis()（保存時刻）
    val transcription: String, // ASR 結果（無音時は空文字）
    val response: String,     // AI 応答（無音・エラー時は空文字）
    val isSilent: Boolean,    // VAD で無音と判定された場合 true
    val errorMessage: String, // ASR / Chat エラー（正常時は空文字）
    val gestureDiags: List<GestureDiagEntry> = emptyList()
)
```

`GestureDiagEntry`: stage / reason / v1–v3 / tMs（セッション相対）/ receivedAtMs

## ジェスチャ診断の取り込み

1. BLE `0x01`（録音開始）で wall-clock 開始時刻を記録
2. ライブ `0x30` は常に `GestureDiagStore` に蓄積（開始ジェスチャは `0x01` より前に届く）
3. BLE `0x02`（録音停止）で時間窓をスライス:
   - 窓: `[start − 8s, stop + 1.5s]`
   - 開始点: 窓内で録音開始以前の **最後の `outbound_start`**（前セッション混入を抑制）
   - `final_sample(0x21)` は除外
   - マイルストーン（match / match_detail / motion_complete / palm_down_gate / reset 等）は優先保持
   - 上限 **60** 件（hold_sample 等から間引き）
4. デバッグ FW の `0x33–0x35` バッチが停止直後にあればそちらを優先
5. 直後の `HistoryEntry` 保存（成功・無音・エラーいずれも）に `gestureDiags` を付与

本番 FW（`GESTURE_DEBUG_HISTORY=0`）でもマイルストーン `0x30` だけで動作する。

### 認識してほしい stage / reason（FW 0.0.68）

| stage | 名前 |
|------:|------|
| `0x09` | match |
| `0x0A` | match_detail（xy / lift_imp / roll_at_lift、reason bit=before_flip\|xy_waive） |
| `0x23` | motion_complete |
| `0x24` | palm_down_gate |

| reason | 名前 |
|------:|------|
| `0x24`–`0x28` | motion_too_slow / palm_down_gravity_low / gyro_angle_low / xy_ratio_low / gate_failed |

詳細行（`historyDetailLine`）の閾値注釈は FW 0.0.68（+imp≥0.30、hold≥500 ms、XY 免除条件）に合わせる。

## 永続化

- **ストレージ**: `SharedPreferences`（`voice_history_prefs` / `history_json`）
- **フォーマット**: JSON 配列。旧エントリに `gestureDiags` が無くても空配列で読む
- **上限**: 最大 100 件（超えた場合は古いものから削除）

## 履歴保存タイミング（VoiceProcessor）

| タイミング | `isSilent` | `transcription` | `response` | `errorMessage` | gestureDiags |
|-----------|-----------|----------------|-----------|---------------|--------------|
| VAD 無音判定 | `true` | 空 | 空 | 空 | キャプチャ済みなら付与 |
| ASR / Chat エラー | `false` | 取得済みのもの | 空 | エラー内容 | 同上 |
| 正常完了 | `false` | ASR 結果 | AI 応答 | 空 | 同上 |

## 画面構成

### 履歴一覧
- 日時の横に診断がある場合 `· ジェスチャN`
- プレビューは従来どおり（無音 / エラー / 文字起こし）

### 履歴詳細
- 文字起こし・AI・エラーの下に **「ジェスチャ判定」**
- 各行: 相対時刻 + 実測値 + 主要閾値（`historyDetailLine()`）
- データなし: `（診断データなし）`

## 画面遷移

```
HOME ──[履歴]──→ HISTORY_LIST ──[タップ]──→ HISTORY_DETAIL
HOME ──[ジェスチャ診断]──→ GESTURE_DIAG（ライブ用・履歴とは別）
```
