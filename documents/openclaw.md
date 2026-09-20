# OpenClaw（LLM）

音声認識は既存の Gemma / Qwen / Groq を使い、応答生成だけを Mac の OpenClaw
Gateway に送る。Terminal Harbor の workspace instruction API は使わない。

## Android の設定

1. ホーム → **モデル設定** → **応答モデル (LLM)** で **OpenClaw** を選ぶ。
2. Gateway URL に、電話から到達できる Mac の private URL を入力する
   （例 `http://<Mac の Tailscale IP>:18789`）。既定の
   `http://127.0.0.1:18789` は同一端末テスト用なので、実機では置き換える。
3. OpenClaw の `gateway.auth.token`（password mode なら password）を入力して保存する。
4. **OpenClaw 接続確認**を押す。

token/password は Android Keystore の AES-256-GCM 鍵で暗号化し、SharedPreferences
には暗号文だけを保存する。要求本文、token、Authorization header はログへ出さない。

## Gateway の設定

Chat Completions は既定で無効なので、Mac で有効化して Gateway を再起動する。

```bash
openclaw config set gateway.http.endpoints.chatCompletions.enabled true
openclaw gateway restart
```

Gateway は operator access と同等のため public internet へ公開しない。電話と Mac を
同じ tailnet に入れ、`gateway.bind=tailnet` など OpenClaw の private remote-access
設定を使う。認証を無効にしない。

今回は既存の Android HTTP バックエンドへ収めるため Gateway Protocol の device
pairing ではなく、OpenAI-compatible endpoint の bearer token fallback を使う。

## セッション

初回に `voice-harness:<UUID>` を1個生成して端末に永続化し、毎回
`x-openclaw-session-key` で再利用する。Gateway は同じ会話を継続するため、各要求では
最新の user turn だけを送る。Harbor コマンド解釈は `<session>:harbor` を使い、通常の
OpenClaw 会話履歴と分離する。

BLE接続されたHarness Nodeの音声、アプリ内マイク音声、アプリ内テキストは、入力元を
区別せず同じ通常セッションキーへ送る。BLE音声は既存のBLE PCM → VAD → ASR →
`BackendAssistantGateway` 経路を通り、アプリ内入力は既存のAssistant Activity経路を
通るが、OpenClaw Gateway上では同じ会話を継続する。

### ブラウザのセッションと共有する

設定の **会話セッションを選択（ブラウザと共有）** で、Gateway 上の既存セッション
（Control UI で開いているもの等）を選べる。選ぶとチャット・BLE音声・アプリ内入力の
送信先とチャット画面の履歴取得が、そのセッションキーになる。「アプリ専用セッションに
戻す」で上記の `voice-harness:<UUID>` へ戻る。Harbor は選択キー + `:harbor` を使う。
`subagent:` / `cron:` / `acp:` 系と `:harbor` 付きは一覧に出さない。

チャット画面は開いたとき・画面に戻ったときに履歴を読み、Gateway の内容で表示を置き換える
（応答生成中と、履歴が空のときは置き換えない）。端末ロック中は読まない。履歴は
`POST /tools/invoke` の `sessions_history`（一覧は `sessions_list`）で取得する。これらが
Gateway の tool policy で許可されていない場合は 404 になるため、`tools.allow` に追加する。
`sessions_history` は表示用に整形されないので、アプリ側で thinking / tool-call XML /
`NO_REPLY` 等を除去している。

応答は、G2プラグイン接続中なら `EvenG2ReadingSession` へ優先表示する。G2未接続時は
音声入力を電話TTS、テキスト入力をアプリ内表示へ届ける。G2表示に失敗した場合は音声
入力だけ電話TTSへフォールバックする。

## API

- `GET /v1/models`: 接続確認
- `POST /v1/chat/completions`: `model=openclaw/default`
- 401 / 403 / 404 / 429 / 5xx は秘密を含まない日本語エラーへ変換
- ダブルタップ等の既存キャンセルは進行中 OkHttp call を cancel
