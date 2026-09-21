# OpenClaw（応答先）

OpenClaw は LLM ではなく、LLM 一覧とは別の「応答先」。音声認識は既存の Gemma / Qwen / Groq
を使い、操作モードが **OpenClaw** のときだけ、応答生成を選択中の LLM の代わりに Mac の
OpenClaw Gateway へ送る。Terminal Harbor の workspace instruction API は使わない。
他のモード（AI対話 / リーダー / Harbor）では従来どおり選択中の LLM を使う。

## Android の設定

1. ホーム → **モデル設定** の **OpenClaw（応答先。LLM とは別）** セクションで設定する
   （応答モデル(LLM)の一覧には出ない）。
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
最新の user turn だけを送る。OpenClaw モードでは Harbor tool を渡さない（Harbor コマンド解釈は
Groq / OpenRouter のみ。旧 `<session>:harbor` キーはコード上に残るが使われない）。

BLE接続されたHarness Nodeの音声、アプリ内マイク音声、アプリ内テキストは、入力元を
区別せず同じ通常セッションキーへ送る。BLE音声は既存のBLE PCM → VAD → ASR →
`BackendAssistantGateway` 経路を通り、アプリ内入力は既存のAssistant Activity経路を
通るが、OpenClaw Gateway上では同じ会話を継続する。

### ブラウザのセッションと共有する

設定の **会話セッションを選択（ブラウザと共有）** で、Gateway 上の既存セッション
（Control UI で開いているもの等）を選べる。選ぶとチャット・BLE音声・アプリ内入力の
送信先とチャット画面の履歴取得が、そのセッションキーになる。「アプリ専用セッションに
戻す」で上記の `voice-harness:<UUID>` へ戻る。
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

## OpenClawモード（G2）

ホームの操作モードボタン（AI対話 / リーダー / Harbor / OpenClaw）か、音声指示
「グラスモード変更 OpenClaw」で入る。Harbor と同じく G2 を必須とせず、Gateway token が
未設定なら有効化できない。OpenClaw は LLM ではないので、入っても応答モデル(LLM)の選択は変わらない。
モードが ON の間は BLE 音声・アプリ内マイク・アプリ内テキストのすべてが OpenClaw へ送られ、
OFF にすると選択中の LLM に戻る。

### Node 音声（Harbor と同じ確認フロー）

BLE の Harness Node に話すと、Harbor モードと同じ手順になる。

1. 音声認識 → G2 に「OpenClawへ送信 / 認識文 / シングルタップで送信 / ダブルタップで取り消す」。
2. シングルタップで送信、ダブルタップで取消（「指示を取り消しました」）。
3. 解釈（LLM）は挟まず、認識文をそのまま送る。応答は G2 に表示し、電話 TTS は使わない
   （G2 未接続のときだけ電話 TTS）。
4. 確認中はミラーが G2 を上書きしない。

アプリ内マイク・アプリ内テキストは確認なしで即送信する。

### G2 の表示

G2 接続中は `OpenClawMirrorController` が約3秒ごとに `sessions_history` を読み、アプリのチャット
画面と同じセッション（`OpenClawPrefs.getChatSessionKey`）のうち **最後に送ったメッセージと、その返信
だけ** を G2 へ映す（`あなた:` / `OpenClaw:`）。読み取り専用で、送信は上記の入力経路が行う。

- 過去のやり取りは出さない。返信は最後の assistant メッセージ。質問は120文字、返信は3,000文字で
  `…` 切り詰め（安全弁。長い返信はプラグインのページ送りで読む）。
- G2プラグインへは通常の `response` として送る。プラグインの変更・再パックは不要。
  確認待ちでなければ、シングルタップはページ送り専用（録音は開始しない）。
- 送信した瞬間（Node 音声・アプリ内マイク・アプリ内テキスト共通）に「あなた: 送信文 /
  OpenClaw: 考え中…」を出す。録音中・認識中・返答待ち・確認待ちはミラーが上書きしない。
- 返答が届くと、その最後のやり取りを即時に描き直す。Gateway がまだ保存していないときは
  質問と返答を補って表示し、次のポーリングで Gateway の内容に置き換わる（同じ表示になるので
  ページは戻らない）。履歴取得に失敗したら従来の返答単体表示（G2失敗時は音声のみ電話TTS）へ
  フォールバックする。
- 送信・準備に失敗したときは G2 にエラーを出す。
- 履歴が空のときは置き換えない。連続で取得に失敗すると G2 に接続エラーを表示する。
- モードは永続化され、サービス再起動後に復元（token未設定ならAIへ戻る）。G2再接続で再開する。
- `sessions_history` が Gateway の `tools.allow` にないと会話は空のまま。
