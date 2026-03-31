# 履歴機能

## 概要

ホーム画面下部に「履歴」ボタンを追加し、過去の録音セッションを一覧・詳細表示できる機能。VAD（Voice Activity Detection）で無音と判定された録音も含めてすべて記録される。

## 追加・変更ファイル

| ファイル | 種別 | 内容 |
|---------|------|------|
| `app/src/main/java/com/g150446/voiceharness/HistoryEntry.kt` | 新規 | 履歴エントリのデータクラス |
| `app/src/main/java/com/g150446/voiceharness/HistoryRepository.kt` | 新規 | SharedPreferences を使った履歴の永続化 |
| `app/src/main/java/com/g150446/voiceharness/VoiceProcessor.kt` | 変更 | 録音完了時に履歴保存フックを追加 |
| `app/src/main/java/com/g150446/voiceharness/VoiceViewModel.kt` | 変更 | 画面遷移状態・履歴 StateFlow を追加 |
| `app/src/main/java/com/g150446/voiceharness/MainActivity.kt` | 変更 | 履歴ボタン・一覧画面・詳細画面を追加 |

## データモデル

```kotlin
data class HistoryEntry(
    val id: String,           // UUID
    val timestamp: Long,      // System.currentTimeMillis()
    val transcription: String, // Whisper 結果（無音時は空文字）
    val response: String,     // AI 応答（無音・エラー時は空文字）
    val isSilent: Boolean,    // VAD で無音と判定された場合 true
    val errorMessage: String  // Whisper / Chat API エラー（正常時は空文字）
)
```

## 永続化

- **ストレージ**: `SharedPreferences`（名前: `voice_history_prefs`、キー: `history_json`）
- **フォーマット**: JSON 配列（`org.json` を使用、依存追加なし）
- **上限**: 最大 100 件（超えた場合は古いものから削除）
- **スレッドセーフ**: `apply()` で非同期書き込み

## 履歴保存タイミング（VoiceProcessor）

| タイミング | `isSilent` | `transcription` | `response` | `errorMessage` |
|-----------|-----------|----------------|-----------|---------------|
| VAD 無音判定 | `true` | 空 | 空 | 空 |
| Whisper / Chat API エラー | `false` | 取得済みのもの（空の場合あり） | 空 | エラー内容 |
| 正常完了 | `false` | Whisper 結果 | AI 応答 | 空 |

## 画面構成

### ホーム画面
- 既存の「Settings」ボタンの下に「履歴」ボタンを追加

### 履歴一覧画面（`HistoryListScreen`）
- 新しい順（降順）でエントリを表示
- 各行: 日時（`yyyy/MM/dd HH:mm`）＋プレビューテキスト
  - 無音: `（無音）`
  - エラー: `[エラー] ` + 先頭 40 文字
  - 通常: transcription の先頭 60 文字（超える場合は `…` を付与）
- エントリをタップすると詳細画面へ遷移
- Android バックジェスチャー対応

### 履歴詳細画面（`HistoryDetailScreen`）
- 日時（`yyyy/MM/dd HH:mm:ss`）
- 無音の場合: `（音声なし）`
- transcription がある場合: 「あなた」ラベル＋全文
- response がある場合: 「AI」ラベル＋全文
- errorMessage がある場合: 「エラー」ラベル＋全文（赤色）
- スクロール対応
- Android バックジェスチャー対応

## 画面遷移

```
HOME ──[履歴ボタン]──→ HISTORY_LIST ──[エントリタップ]──→ HISTORY_DETAIL
  ↑                         ↑                                    │
  └─────────────────────────┴────────────[戻るボタン / バックジェスチャー]
```

`AppScreen` enum（`HOME` / `HISTORY_LIST` / `HISTORY_DETAIL`）を `VoiceViewModel` の `StateFlow` で管理し、Compose の `key(currentScreen)` でスコープを分けることで画面切り替え時のレイアウトノード破棄を確実に行う。

## 実装上の注意点

- **`key(currentScreen)` の使用**: Scaffold の `SubcomposeLayout` が画面切り替え時に切り離されたノードを再測定しようとする問題（`LayoutNode should be attached to an owner`）を防ぐため必須
- **`indication = null` の指定**: `clickable` に何も指定しないと Material の `PlatformRipple` が渡され、Compose Foundation との互換性エラー（`IllegalArgumentException`）が発生するため、`indication = null` と `MutableInteractionSource` を明示的に指定する
