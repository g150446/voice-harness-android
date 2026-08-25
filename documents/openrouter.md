# OpenRouter（LLM）

応答生成だけを **OpenRouter** の Chat Completions で行うクラウド LLM バックエンド。  
音声認識（ASR）には使わない。ASR は Gemma / Qwen / Groq を別途選ぶ。

## 前提

- インターネット接続
- OpenRouter API キー
- モデル設定で **応答モデル = OpenRouter** を明示選択
- 利用するモデル ID を一覧から明示選択（自動切替なし）

既定の ASR/LLM は OpenRouter 導入後も変わらない（オプトイン）。

## 設定手順

1. ホーム → **モデル設定**
2. **応答モデル (LLM)** で OpenRouter を選択
3. API キーを入力して保存（Android Keystore の AES-256-GCM で暗号化し、SharedPreferences には暗号文のみ）
4. **モデル一覧を更新** → 検索 → モデルを選択
5. （任意）無料 / 画像 / tools バッジで絞り込み

選択済みモデルが一覧から消えた場合は自動置換しない。再選択が必要。

## 機能

| 項目 | 内容 |
|---|---|
| API | `GET /api/v1/models`, `POST /api/v1/chat/completions` |
| モデルキャッシュ | 24 時間。更新失敗時は既存キャッシュ維持 |
| tools | `supported_parameters` に `tools` があるモデルだけ `set_reminder` を送る |
| 画像 | 画像対応モデルかつ「画面を使用」ON のとき、最後の user メッセージに JPEG（text 先行）を添付 |
| 画面テキスト | ローカル/Groq と同様、一時プロンプトとして添付（履歴には残さない） |
| キャンセル | シートを閉じると進行中 HTTP Call を cancel |

## 関連ファイル

| ファイル | 役割 |
|---|---|
| `OpenRouterLlmBackend.kt` | Chat + models fetch |
| `OpenRouterChatRequestBuilder.kt` | リクエスト JSON / レスポンス解析 |
| `OpenRouterModels.kt` | カタログ解析・検索・バッジ判定 |
| `OpenRouterPrefs.kt` | 暗号 API キー・モデル ID・キャッシュ |
| `SecureApiKeyStore.kt` | Keystore AES-GCM |
| `GroqSettingsActivity.kt` | 設定 UI |

## ログ

```bash
adb logcat -s OpenRouterLlmBackend:D OnDeviceAiFacade:D AssistantSessionCtrl:D
```

API キー、要求本文、画面 JPEG はログに出さない。

## 実装計画

詳細仕様は [`voice-harness-android-openrouter-plan.md`](./voice-harness-android-openrouter-plan.md) を正本とする。
