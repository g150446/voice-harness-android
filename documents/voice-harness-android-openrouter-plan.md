# OpenRouter・画面コンテキスト・対話型アシスタント統合計画

## Summary

- `voice-harness-android` の `integration/opendroid` ブランチと単一 `:app` 構成を維持する。
- ASRと応答LLMを独立選択可能にし、LLM側へOpenRouterを追加する。
- 電源ボタン長押しで、OpenDroid相当の半透明な下部アシスタント画面を表示する。
- 呼び出し直後には自動録音せず、テキスト入力またはマイクボタンから送信する。
- Androidが提供した画面テキストとスクリーンショットを、その呼び出し中だけ任意で回答に利用する。
- HarnessNode経路は画面UIを出さず音声処理する。デフォルトアシスタント時は録音開始時にヘッドレス VoiceInteraction で画面テキスト／スクショを取得し、自アプリ画面・ロック・画面オフでは添付しない。
- AgentLoop、Accessibilityによるタップ・スクロール、操作計画・承認UIは今回含めない。

## Implementation Changes

### 対話型アシスタント画面

- `HarnessVoiceInteractionSession` はデフォルトのセッションUIを無効化し、`startAssistantActivity()` でVoice Harness専用の非公開Activityを起動する。
- Activityは全画面透明ウィンドウ上の下部シートとして表示し、次を提供する。
  - Voice Harness名と取得元アプリ名
  - 会話メッセージ一覧
  - 「画面を使用」ON/OFFチップ
  - 複数行テキスト入力欄
  - マイク開始／停止ボタン
  - 送信ボタン
  - 待機、認識中、生成中、読み上げ中、エラー状態
  - 閉じるボタンとシート外タップによる終了
- 呼び出しごとに新しい会話IDを生成する。同じシートを開いている間は複数ターンを保持し、閉じると画面メモリと表示履歴を破棄する。
- マイクボタンは既存の外部`RecognitionService`選択処理を再利用する。部分認識を入力欄へ表示し、確定結果を音声由来として送信する。
- テキスト送信は回答を画面へ表示するが、原則TTSしない。マイクから送った質問は回答を表示し、既存の電話／スマートグラス出力設定に従ってTTSする。
- Activity、VoiceInteractionSession、`BleConnectionService`間はプロセス内の`AssistantSessionController`で接続する。Controllerが`StateFlow<AssistantUiState>`、リクエストID、会話ID、メッセージ、認識・生成状態を管理する。
- バックまたは閉じる操作では認識を停止し、進行中リクエストをキャンセル扱いにして、遅れて返った結果やTTSを表示・再生しない。OpenRouterのHTTP Callは実際にキャンセルし、キャンセル不能なローカル推論結果は破棄する。
- Activityは履歴画面や最近使ったアプリへ残さず、画面回転・再生成時は同じControllerへ再接続する。セッション自体が終了した場合はActivityも閉じる。
- ロック画面呼び出しは既存の`ROLE_ASSISTANT`設定を維持するが、過去の会話や画面内容を表示しない。OSが許可する範囲だけで入力画面を表示し、独自のロック解除回避は行わない。

### ASR・LLMの独立化

- 次の選択型を導入する。
  - `SttBackendId { GEMMA, QWEN, GROQ }`
  - `LlmBackendId { GEMMA, QWEN, GROQ, OPENROUTER }`
- 既存の単一プロファイル設定を、初回のみASR・LLM双方へコピーする冪等な移行処理を追加する。移行後は独立キーを正本とし、既存ユーザーの動作を変えない。
- Gemma・Qwen・GroqはASR/LLM両契約へ適合させる。共有バックエンドレジストリを使い、同じローカルモデルが両方で選ばれても二重ロードしない。
- 設定変更で不要になったバックエンドだけ解放し、ASR側の変更で進行中のLLM会話を失わない。
- モデル設定画面を「音声認識」と「応答モデル」に分け、現在の準備状態とロード時間をそれぞれ表示する。

### 公開契約

- 一時画面情報を次の型で扱う。
  - `ScreenContext(assistText, sourcePackage, sourceUri, jpegBytes, capturedAt)`
  - `ChatRequest(conversationHistory, languageCode, screenContext)`
  - `AssistantRequest.screenContext: ScreenContext?`
- `LlmBackend.chat` は`ChatRequest`を受け取る。
- `AssistantRequest`にリクエストID、会話ID、入力元、読み上げ有無を保持する。
- `AssistantUiState`は、画面の有効状態、会話ID、画面取得元、画面利用ON/OFF、メッセージ一覧、入力・生成状態、エラーを保持する。
- 画面コンテキストは会話ターンや履歴へ追加せず、各`ChatRequest`にだけ添付する。

### OpenRouter

- `OpenRouterLlmBackend`、モデルカタログ、モデル選択UIを追加する。OpenRouterへ自動切替せず、APIキーとモデルの明示選択を必須とする。
- APIキーはAndroid KeystoreのAES-256-GCM鍵で暗号化し、SharedPreferencesにはバージョン付き暗号文だけを保存する。復号不能時は再入力を要求し、ログ、Intent、履歴、例外表示へ出さない。
- `GET /api/v1/models`からID、表示名、コンテキスト長、価格、入力モダリティ、`supported_parameters`を取得する。24時間キャッシュ、手動更新、検索、無料・画像・tools対応バッジを実装する。
- 更新失敗時は既存キャッシュを維持する。選択済みモデルが最新一覧から消えた場合は自動置換せず、再選択を要求する。
- `POST /api/v1/chat/completions`へ会話履歴を送る。画像対応モデルでは最後のユーザーメッセージをテキストとBase64 JPEGのcontent配列にし、テキストを画像より先に配置する。
- `supported_parameters`に`tools`があるモデルだけ既存の`set_reminder`ツールを送信し、返却された`tool_calls`を既存のリマインダー処理へ渡す。
- HTTPエラーはステータスと安全な概要へ変換し、APIキー、要求本文、画面情報をログへ記録しない。

### 画面コンテキスト

- `onHandleAssist`の現行・旧APIと`onHandleScreenshot`を処理する。
- AssistStructureのtext、contentDescription、hintを順序維持・重複排除して24,000文字まで抽出し、取得元packageとWeb URIも保持する。
- スクリーンショットはJPEG品質75へ変換し、メモリだけに保持する。
- シート上の「画面を使用」は、画面情報が届いた場合のみ操作可能とし、呼び出しごとの既定値はONとする。
- ユーザー送信時に不足している画面コールバックを最大500ms待ち、その時点で利用可能な情報だけを添付する。
- JPEGをIntentへ入れず、UUIDトークンを使うプロセス内ストアでサービスへ渡す。保存物は一回消費または30秒で失効し、キャンセル・セッション終了・送信失敗時にも削除する。
- ロック中は画面情報をすべて破棄する。`FLAG_SECURE`やOS制限でスクリーンショットが得られなければ画面テキストだけ、どちらもなければ通常質問として処理する。
- Groq・Qwen・Gemmaへは画面テキストだけを一時プロンプトとして渡し、JPEGは画像対応が確認できるOpenRouterモデルだけへ送る。
- `HARNESS_NODE_VOICE`も取得できた `ScreenContext` をGatewayへ通す（空のみ破棄）。自アプリ除外はキャプチャ側で行う。
- HarnessNode は `VoiceInteractionService.showSession(SHOW_WITH_ASSIST|SHOW_WITH_SCREENSHOT)` のヘッドレスセッションで取得する（Activity なし）。`ROLE_ASSISTANT` 未保持・画面オフ・自アプリ UI ではスキップ。

### 録音フィードバック（HarnessNode）

- 開始/終了キュー音は `RecordingCuePlayer`。`USAGE_MEDIA` で TTS と同じストリームに載せ、マナーモードでも聞こえるようにする（旧 `USAGE_ASSISTANCE_SONIFICATION` は SYSTEM/NOTIFICATION ミュートで無音だった）。
- 他アプリ上の録音インジケータは `RecordingOverlayController`（`TYPE_APPLICATION_OVERLAY`）。`SYSTEM_ALERT_WINDOW` が必要。未許可時は FGS 通知を「録音中…」にする。

## Test Plan

- 電源ボタン長押しからActivity起動、手動マイク開始、部分認識、テキスト送信、会話継続、閉じる、バック、外側タップ、回転再生成をテストする。
- テキスト入力ではTTSしないこと、マイク入力ではTTSすること、キャンセル後の遅延結果が表示・再生されないことを確認する。
- 旧プロファイルのASR/LLM移行、独立切替、非上書き、共有モデルの二重ロード防止を単体テストする。
- OpenRouterモデル解析、無料・画像・tools判定、検索、24時間キャッシュ、失敗時キャッシュ維持、廃止モデル検出をテストする。
- MockWebServerでテキスト、画像、ツール、ツール非対応、HTTPエラー、キャンセル、APIキー非漏洩を検証する。
- AssistStructure抽出、24,000文字制限、コールバック結合、500ms待機、画面利用OFF、一回消費、30秒失効、IntentにJPEGがないことをテストする。
- HarnessNode経路がアシスタントUIを開かず、条件付きで画面情報を添付し、従来どおりTTS・履歴・リマインダーを処理することを回帰テストする。
- `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`を完走させる。
- Android 16実機で、通常画面、IME表示、マイク権限拒否、画像非対応モデル、`FLAG_SECURE`、ロック画面、画面オフ、プロセス再起動、HarnessNode同時接続を受け入れ確認する。

## Assumptions

- 今回表示するのはOpenDroid相当の会話・入力画面であり、端末操作用AgentLoopや計画承認UIは対象外とする。
- 電源ボタン長押し直後には音声認識を自動開始せず、ユーザーがマイクボタンを押したときだけ開始する。
- OpenRouterは明示的オプトインとし、新規・既存ユーザーの既定ASR/LLMは変更しない。
- 画面取得はAndroidのVoiceInteraction APIが提供した情報だけを使用し、Accessibility ServiceやMediaProjectionによる別経路は追加しない。
- 旧`documents/gemini_alternative_plan.md`は実装着手時に削除し、本計画を実装の正本とする。
