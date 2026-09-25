# Voice Harness

Android アプリ。XIAO nRF52840 Sense をウェアラブルマイクとして使い、オンデバイス / Groq / OpenRouter で音声認識・AI応答を行い、Android TTS（または Even Realities G2）で出力する。電源長押しのデジタルアシスタント（下部シート UI）にも対応する。

## 概要

```
[Harness Node]
  ・シングルタップ (0x14) → G2未接続時のみホストが RX 0x01/0x00（AI対話の既定録音操作）。G2接続中は録音を開始/終了しない（リーダー／Harbor要約中のページ送り、Harbor確認待ちの実行にのみ使う）
  ・手首ジェスチャー → オプション（既定OFF）。ON 時のみ FW 自律 TX 0x01 / 0x02
  ・ダブルタップ (0x12) → G2接続中はモード別の指示録音（先頭が「グラスモード変更」のときだけモード切替）。未接続時はホーム設定で録音開始／終了／無視
        │ BLE TX 0x01 (録音開始)
        ▼
[Android: PCM 蓄積]
  ・開始/終了キュー音（MEDIA 経路・ホームでトグル・既定オフ）
  ・他アプリ上の録音オーバーレイ（要 SYSTEM_ALERT_WINDOW）
  ・G2未接続かつダブルタップ録音時は通常録音／処理をキャンセル。G2接続中のモード指示録音は確定
  ・ROLE_ASSISTANT 時はヘッドレス Assist で画面テキスト/スクショ取得
        │ BLE TX 0x02（録音停止）
        ▼
[Silero VAD + FFT fallback]
        │ 音声あり
        ▼
[ASR: Gemma / Qwen3-ASR / Groq Whisper] → 文字起こし
        │
        ▼
[Chat: Gemma / LFM 2.5 / Groq / OpenRouter] → AI 応答
  （取得できた ScreenContext を添付。自アプリ/ロック/画面オフは除外）
        │
        ▼
[Android TTS or Even G2] → 出力

[リーダーモード]
  ・G2 プラグイン接続中のみ ON 可。切断で自動 OFF。切替は G2 に表示
  ・ホームのユーザー補助（Accessibility）必須（Kindle 自動／ページめくり）
  ・G2 プラグインが実表示幅と句読点で本文を分割、Node シングルタップで送り
  ・次のG2画面に本文が足りない場合はKindleを自動でめくり、次ページ本文を結合

[Terminal Harborモード]
  ・Terminal Harborの既存モバイルブリッジとQR/HMACでペアリング
  ・Android画面で複数Mac、workspace/tab、terminal出力、文字・音声入力、キー送信を操作
  ・HarnessNodeとAndroidだけでも利用でき、G2は同じworkspaceを表示する任意の追加出力先
  ・出力中は空白・罫線行を省いた末尾をライブ表示
  ・入力待ちになると日本語要約（最大3画面）＋質問・選択肢を原文表示
  ・音声指示はSTTをG2即表示→LLM意図確認→タップで送信（モードは再起動後も復帰）

[電源長押し ROLE_ASSISTANT]
        → 下部シート UI（自動録音なし）
        → テキスト or マイク送信
        → 任意で画面テキスト / スクリーンショットを添付
        → 同じ AssistantGateway → LLM
```

**FW は `0.0.94+` を前提**（single/double は notify-only）。アプリとセットで更新する。

Mac Handy と同時接続する場合、ホームの **優先接続** で音声の送り先（Node の primary）を選ぶ。既定は Android。タップ回数は secondary にも届くが、録音開始イベントと PCM は primary のみ。詳細は [`documents/ble_protocol.md`](documents/ble_protocol.md)。

**モデル設定** で ASR と LLM を独立に選択する（旧プロファイルは初回のみ双方へコピー）:

| 役割 | 選択肢 |
|---|---|
| 音声認識 (ASR) | Gemma 4 E2B / Qwen3-ASR / Groq Whisper |
| 応答モデル (LLM) | Gemma 4 E2B / LFM 2.5 / Groq Chat / **OpenRouter** |

OpenRouter は明示オプトイン（API キー + モデル選択が必須）。既定 ASR/LLM は変更しない。

電話マイクでの録音も可能（BLE 未接続時のフォールバック）。

---

## セットアップ

### 必要なもの

- Android 12 以上 (API 31+)
- XIAO nRF52840 Sense（`harness-node/nordic-main` ファームウェア書き込み済み）
- ローカル利用時: Qwen / Gemma / LFM のオンデバイスモデル（詳細は下記ドキュメント参照）
- クラウド利用時: Groq API キー、および/または OpenRouter API キー（モデルファイル不要）
- デジタルアシスタント利用時: 設定アプリで Voice Harness をデフォルトのデジタルアシスタントに指定
- （任意）Even Realities G2とEven Realities App、Even Hubプラグイン（AI返答をグラスへ表示する場合）

### アプリのインストール

```bash
git clone https://github.com/g150446/voice-harness-android.git
cd voice-harness-android
./scripts/prepare-qwen-asr-native.sh
# Large debug APK: prefer USB (Tailscale install often times out and leaves a stale process)
./scripts/install-usb.sh
```

Tailscale は **logcat / 小ファイル向け**。APK インストールは USB を推奨:

```bash
./scripts/install-usb.sh          # USB install + tcpip 5555
./scripts/adb-tailscale.sh connect
```

### 初期設定

1. アプリを起動し、要求された権限（Bluetooth・マイク・通知）を許可する
2. 画面下部の **モデル設定** で ASR と LLM をそれぞれ選ぶ
   - **ローカル**: `models/` を配置し `./scripts/push-all-models.sh`、または画面から取り込み → モデルを読み込む
   - **Cloud (Groq)**: API キーを入力して保存
   - **OpenRouter（LLM のみ）**: API キー保存 → モデル一覧更新 → モデルを明示選択
3. （推奨）デジタルアシスタント: システム設定で Voice Harness をアシスタントに設定
   - 電源長押しで下部シート
   - HarnessNode ジェスチャー時の **画面テキスト／スクショ自動添付** にも必要
4. （推奨）**録音中アイコンの表示を許可**（他のアプリの上に表示）
   - 他アプリ表示中に赤いマイクオーバーレイで録音中を示す

### nRF52840 との接続

ペアリング設定は不要。アプリ起動後に自動スキャンが始まり、対象デバイスを見つけると自動接続する。

- **BLE Scanning...** → スキャン中（青ドット）
- **BLE Connecting...** → 接続中（オレンジドット）
- **BLE Connected** → 接続完了（緑ドット）
- **BLE Off** → 未接続（グレードット）

30 秒スキャンしても見つからない場合は指数バックオフ（2 秒→4 秒→…最大 60 秒間隔）で自動リトライする。

---

## 使い方

### BLE 録音（nRF52840）

既定は **シングルタップのみ**（ジェスチャー録音・IMU収集・運転自動判定はいずれもオフ）。
ホームの「ジェスチャー録音」を ON にすると手首ジェスチャーも使える（Node FW `0.0.95+`）。

1. Node をシングルタップ（またはジェスチャー ON 時は録音ジェスチャー）
2. PCM を蓄積（ホーム「録音キュー音」がオンのときのみ **開始キュー音**）
   - 他アプリ上では赤いマイク **オーバーレイ**（許可時）と通知「録音中…」
   - 自アプリ UI では **Recording (BLE)...**
3. 再度タップ（または停止ジェスチャー）で録音終了（オン時のみ **終了キュー音**）→ `Silero VAD` → テキスト化・AI 応答・読み上げ  
   G2未接続かつダブルタップ録音設定時、録音中〜応答中の **ダブルタップ** はキャンセル（「中断しました」）。  
   G2接続中のダブルタップはモード指示録音（同録音中は確定、通常処理中は中断）。  
   double 直後 **2 秒**は single を無視（遅延 start Job も cancel。残振動による再録音防止）
4. デフォルトアシスタント設定時、録音開始時点の **他アプリ画面**（Assist テキスト + スクショ）を LLM に添付
   - 自アプリ画面・ロック・画面オフでは添付しない
   - JPEG は OpenRouter の画像対応モデルのみ。他バックエンドは画面テキストのみ
5. 結果は **履歴** に保存される。ジェスチャ時は FW 診断（`0x30`）も同じ履歴に付く

AI が読み上げ中に再度録音操作を行うと、読み上げを中断して新しい対話を開始できる。

キュー音は **既定オフ**（ホームでトグル）。オン時は **メディア音量**（TTS と同じ経路）。

ライブの診断ストリームはホームの **ジェスチャ診断**、仕様は `documents/history_feature.md` / `documents/ble_protocol.md`。

### 電話マイク録音（手動）

1. **● Record (Mic)** ボタンをタップして録音開始
2. **■ Stop** をタップして録音停止 → 自動でテキスト化・AI 応答・読み上げ

読み上げ中は **■ Stop Speaking** で中断し、すぐに新しい録音を開始できる。

### AI返答の出力先

ホーム画面の **AI返答の出力先** で、従来のTTS音声とEven Realities G2を切り替えられる。
G2を選ぶとAI返答をEven Hubプラグイン経由でグラスへ表示し、電話画面と履歴にも返答を残す。
Even Hubプラグインが未接続の場合は、自動的にTTSへ戻して返答を読み上げる。
G2側にトグルしたままプラグインのポーリングが途絶える（切断）と、約2秒後に出力先の設定自体も
自動的に音声へ戻り、次の返答から読み上げになる。再接続しても自動ではG2へ戻らないため、
使う場合は手動で切り替える。

G2を利用する前にEven Realities Appでグラスをペアリングし、EvenHubからインストールした
**Voice Harness**プラグインを起動する。アプリ内の **Even Realities Appを開く** ボタンから
コンパニオンを起動できる。インストール後の通常利用にMacや開発サーバーは不要。

QR/URLと`npm run dev`はプラグイン開発時だけ使用する。配布パッケージの作成・非公開登録と
開発セッション終了時の注意事項は[`even-g2/app/README.md`](even-g2/app/README.md)を参照。

### G2操作モード

ホームの「操作モード」で **AI対話 / リーダー / Harbor / OpenClaw / EPUB** を切り替えられる（EPUB は [`documents/epub_reader.md`](documents/epub_reader.md)）。
リーダーとTerminal Harborは、それぞれの欄にあるトグルからもON/OFFできる。
選択したモードは端末に保存され、アプリ再起動後も G2 再接続と（Harbor の場合）ペアリングが揃えば自動復帰する。
G2接続中のダブルタップは、今の操作モード向けの指示録音になる。リーダーでは「5つページ進めて」
のように Kindle 実ページを操作し、Harbor では STT を G2 に即表示したうえで LLM が意図を確認し、
確認内容だけを電話のTTSでも読み上げる。AIが解釈している間、電話の画面には
「AIが指示を確認中…」（回転インジケータ・聞き取った指示・取り消す）を出し、確認メッセージが
届いたら通常の確認カードに切り替わる。シングルタップで Terminal Harbor へ送信、
ダブルタップで取り消し、受け付け時にはそれぞれ「実行します」「キャンセルします」と読み上げる。
実行は読み上げと同時に開始する。モード切替は発話の先頭を
「グラスモード変更」にしたときだけ。G2未接続時のダブルタップはホームの録音タップ設定に従う。
AI対話モードはG2出力を自動選択する。Harbor がペアリング済みなら AI 対話でも `harbor_command`
tool で端末操作を受け付け、拒否せず確認画面へ進む（クラウド LLM が必要）。
履歴には操作モード・G2接続有無・ASR/LLM モデルが残る。

Harborモードを初めて使う場合は、Terminal Harborのサイドバーで **Pair mobile** を開き、
Voice HarnessのTerminal Harbor欄からQRを読み取る。QRを使えない場合はPair URIを手入力できる。
ペアリング情報はAndroid Keystoreで暗号化され、Pair URI、認証鍵、端末本文はログや履歴へ保存しない。
ブリッジはTailscaleまたは信頼できるLAN上のHTTPを使用し、HMACで相互認証と改ざん検出を行う。
ホームの **Terminal Harborを開く** から、ペア済みMacとworkspaceの選択、workspace/tabの作成・終了、
terminal出力の閲覧、文字・Android音声認識による指示、矢印・Esc・Ctrl-C・Tabキーの送信ができる。
キーのボタン群は既定では畳まれていて、入力欄の上の **キー ▾** で開く。
音声認識は入力欄へ追記するだけで自動送信しない。履歴表示中はライブ更新を停止し、Liveへ戻すと再開する。
terminal出力の文字サイズはヘッダのA−/A+で変更でき（8〜24sp）、端末に保存される。
端末幅いっぱいの横罫線は表示幅に合わせて縮めるので、区切り線が何行にも折り返らない。
表示は **端末 / プラン / 会話** を切り替えられる。

- **プラン**: いま動いているエージェントが書いたプランファイル全体（`GET /v1/workspaces/{id}/plan`、
  Terminal Harbor API 1.11.0 以上）。
- **会話**: エージェントとのやりとり（`GET /v1/workspaces/{id}/transcript`、API 1.12.0 以上）。
  **AI エージェントの TUI は画面を描き直すため端末のスクロールバックに履歴が残らず、端末表示では
  1画面分しか遡れない。** 会話表示はエージェント自身のセッションログを読むので、返答から
  それを生成した指示まで遡れる。上端の **さらに遡る** で古い分を読み足す。ツール呼び出し・
  ツール出力・思考・注入された文脈は省き、人の発言とエージェントの返答だけを出す。

どちらも端末画面から拾うのではなくファイルを読む。Claude Code 側に
`wezterm agent-session install-hooks --agent claude --apply` で hook を入れておく必要があり
（Codex は不要）、取得できない場合は理由を表示する（端末出力で代用はしない）。
旧Terminal Harbor Mobile Android版の認証情報はアプリ間で移送せず、Voice Harnessから再ペアリングする。

HarborモードはG2未接続でも有効にできる。HarnessNodeのダブルタップで指示録音を開始・終了し、
解釈後はシングルタップで実行、ダブルタップで取り消す。同じ操作はホームとworkspace画面の
確認ボタン（実行／取り消す／言い直す）からも行える。
G2を接続した場合はMacで現在選択しているワークスペースのアクティブペインを追従する。出力中は
空白行・罫線・点線だけの行を省いた末尾を表示する。画面が停止してAIエージェントが質問や選択肢を
表示すると、Terminal Harborに設定されたOpenRouterモデルが直前の指示以降を日本語で要約する。
要約は最大3画面で、Harness Nodeのシングルタップで次画面へ進む。質問と選択肢は最後に原文で残る。
音声報告は画面要約とは別に、確認済みの音声指示を実行したworkspaceだけを完了まで追跡する。
Androidは実行直前の画面を基準に、変化後2秒間安定した画面と最新会話を、音声指示を解釈したのと
同じworkspace別OpenClawセッションへ渡す。完了、質問、権限、選択肢待ちを最大2文・160字で
一度だけ読み上げる。`not_waiting` や一時障害では追跡を終了せず、同じ画面を間隔を空けて再確認する。
G2未接続でも追跡し、OpenClaw障害時だけ既存のTerminal Harbor要約へフォールバックする。
Mac側から直接開始した作業は報告対象にせず、新しい音声指示なら同じ結果文でも再報告する。

> Vuzix Z100向け実装（`SmartGlassesOutputManager`）は将来再配線用にコードとSDK依存を残しているが、
> 実行パスからは外している。詳細は `documents/smart_glasses_output.md`。

---

## ビルド・開発

```bash
# Qwen3-ASR用runtimeを準備（初回のみ）
./scripts/prepare-qwen-asr-native.sh

# テストとデバッグビルド
./gradlew testDebugUnitTest :app:assembleDebug

# デバッグビルド
./gradlew :app:assembleDebug

# デバイスへインストール（~180MB debug APK は USB 推奨）
./scripts/install-usb.sh

# ログ確認（VAD・BLE・録音 UI / キュー音）
adb logcat -s VoiceProcessor SileroVad BleManager BleConnectionService \
  RecordingCuePlayer RecordingOverlay HeadlessScreenCapture HarnessVoiceSession
```

### ADB（USB / Tailscale）

```bash
# APK インストールは常に USB（不完全インストールで BLE 状態が壊れやすい）
./scripts/install-usb.sh

# 初回または再起動後: USB で TCP 5555 を有効化し Tailscale 接続
./scripts/adb-tailscale.sh enable-usb

# USB を抜いたあと / 別 Mac から: Tailscale IP で再接続のみ（logcat 等）
./scripts/adb-tailscale.sh connect

./scripts/adb-tailscale.sh status
./scripts/adb-tailscale.sh disconnect
```

- 既定ホスト名: `motorola-razr-50s`（`TS_HOST=...` で変更可）
- 既定ポート: `5555`
- Tailscale 上で端末が online であること（`tailscale status`）
- **APK を Tailscale 経由で入れない**（タイムアウトで古いプロセスが残ることがある）

`connect` は Tailscale の 5555 → mDNS 探索 → USB 手順の案内、の順に試す。
**端末を再起動すると `adb tcpip` は解除される**（`persist.adb.tcp.port` は root 無しでは
設定できない）。`enable-usb` は tcpip の有効化に加えて Android 標準の
**ワイヤレスデバッグ**（`settings global adb_wifi_enabled`）も ON にする。これは再起動後も
残るので、Mac と端末が同じ Wi-Fi にいれば `connect` が mDNS で自動的に見つける。
mDNS はリンクローカルなので tailnet は越えない。**同じ Wi-Fi にいない状態で端末を再起動した
場合だけ、USB を一度挿して `enable-usb` が必要**。

ローカル Wi-Fi / テザリングだけで繋ぐなら `./scripts/adb-wireless.sh`：

```bash
./scripts/adb-wireless.sh            # 無線で接続。失敗したら USB セットアップに落ちる
./scripts/adb-wireless.sh connect    # 無線のみ（USB 不要）
./scripts/adb-wireless.sh setup      # USB 接続時: tcpip 5555 を有効化して接続・IP を記憶
./scripts/adb-wireless.sh status
```

`connect` は **USB なしで**次の順に試す:

1. 前回成功した IP（`~/.cache/voice-harness/adb-wireless.host`）
2. Mac のデフォルトゲートウェイ — 端末のテザリングに Mac が繋がっている場合はこれが端末 IP
3. 端末の Tailscale IP

`adb tcpip 5555` は**端末を再起動するまで有効**なので、一度 `setup` すれば以降は USB 不要。
再起動後に繋がらなくなったら USB を挿して `setup` をやり直す。

**APK インストールを USB 推奨としているのは tailnet 経由の話**で、リンクローカルの無線
（テザリング / 同じ Wi-Fi）はそうでもない。2026-09-23 に 144MB の debug APK を
テザリング経由（候補2番のゲートウェイ IP）で入れて **29 秒**だった。tailnet の IP に繋がったまま
入れるとタイムアウトするので、無線で入れるなら `adb devices` でどちらのトランスポートに
向いているかを確認してから。

### VAD / ASR 幻覚対策メモ

- BLE 経路は `Silero VAD` を優先し、`maxProb` が異常に低い場合は FFT ベース解析に自動フォールバックする
- FFT 側は無音フレームを除いた「アクティブフレーム」基準で判定する
- Silero が stuck のときだけ、小声の実測振幅以上ならエネルギー救済する。無音ノイズは拒否する
- 録音停止はファームウェアの停止ジェスチャ（TX `0x02`）のみ。Android は無音で RX `0x00` を送らない
- `ちいかわ` 系語彙は 1 パス目に載せない。転写に `アニメ` があるときだけ 2 パス目で Preferred spellings を付与する
- `AsrTextFilter` が語彙エコー（アニメ無しの3語列挙）と儀礼句だけの幻覚（`はい、ありがとうございます` など）を破棄する

### 主要ファイル

| ファイル | 役割 |
|---|---|
| `BleManager.kt` | BLE スキャン・接続・パケット解析 |
| `BleConnectionService.kt` | BLE をフォアグラウンドサービスとして管理 |
| `VoiceProcessor.kt` | 録音制御・停止後 VAD・AI・TTS |
| `SilenceEndpointTracker.kt` | （未使用）連続無音カウンタ。停止は FW ジェスチャのみ |
| `AsrVocabulary.kt` | ASR Preferred spellings とトリガー語（アニメ） |
| `AsrTextFilter.kt` | 語彙エコー・儀礼句など ASR 幻覚の破棄 |
| `ModelManager.kt` | モデル探索、取り込み、ASR/LLM 独立設定 |
| `OnDeviceAiFacade.kt` | ASR/LLM 独立ルーティング + 共有 BackendRegistry |
| `QwenOnDeviceBackend.kt` | Qwen3-ASR + LFM 2.5（LEAP） |
| `GemmaOnDeviceBackend.kt` | Gemma 4 の実行 |
| `GroqVoiceAiBackend.kt` | Groq Whisper + Chat Completions |
| `OpenRouterLlmBackend.kt` | OpenRouter Chat Completions（LLM のみ） |
| `GroqPrefs.kt` / `OpenRouterPrefs.kt` | API キー保存（OpenRouter は Keystore 暗号化） |
| `assistant/*` | デジタルアシスタント Session / Activity / 画面コンテキスト |
| `assistant/HeadlessScreenCapture.kt` | HarnessNode 用ヘッドレス Assist + スクショ取得 |
| `RecordingOverlayController.kt` | 他アプリ上の録音中オーバーレイ |
| `RecordingCuePlayer.kt` / `RecordingCuePreferences.kt` | 録音開始/終了キュー音（既定オフ・ホームでトグル） |
| `BleSpeechDetector.kt` | BLE PCM の DC 除去、FFT フォールバック、スペクトル解析 |
| `EvenG2ReadingSession.kt` / `EvenG2BridgeServer.kt` | Even G2 表示セッションと loopback ブリッジ |
| `HarborIntegration.kt` | Terminal Harbor ペアリング・APIクライアント・ミラー制御（複数Mac対応、資格情報は Keystore 暗号化） |
| `HarborCommandTool.kt` / `HarborInterpretContext.kt` | `harbor_command` tool の意図解析と workspace コンテキスト |
| `HarborFontSizePreferences.kt` | Terminal Harbor 画面の文字サイズ永続化（8〜24sp） |
| `SmartGlassesOutputManager.kt` | Vuzix Z100 実装（実行未使用・将来再配線用アーカイブ） |
| `MainActivity.kt` | UI（Jetpack Compose） |
| `GroqSettingsActivity.kt` | モデル設定画面（ASR/LLM 独立 + OpenRouter） |

---

## 詳細ドキュメント

- [`documents/ble_protocol.md`](documents/ble_protocol.md) — BLE パケット仕様（ジェスチャ診断 `0x30` 含む）
- [`documents/gesture_detect_default_off.md`](documents/gesture_detect_default_off.md) — ジェスチャー/IMU/運転判定の既定 OFF（0.0.95）
- [`documents/gesture_false_trigger.md`](documents/gesture_false_trigger.md) — 誤発火解析と発話ゲート
- [`documents/history_feature.md`](documents/history_feature.md) — 会話履歴とジェスチャ判定の保存・UI
- [`documents/ble_audio_reliability.md`](documents/ble_audio_reliability.md) — Bluetoothヘッドセット併用時の音声経路、PCM送達保証、障害調査
- [`documents/smart_glasses_output.md`](documents/smart_glasses_output.md) — Even G2 出力（現行）と Vuzix Z100 アーカイブ仕様
- [`documents/harbor_confirm_voice_intent.md`](documents/harbor_confirm_voice_intent.md) — Harbor 確認画面のタップ割り当てと意図解析を1回にした経緯
- [`documents/harbor_mode_switch.md`](documents/harbor_mode_switch.md) — Claude Code のモード切替で ⇧Tab の回数を数えるのをやめ、画面を読みながら送るようにした経緯
- [`documents/reader_harbor_mode_conflict.md`](documents/reader_harbor_mode_conflict.md) — リーダーモードがHarborモードに引き戻される回帰の原因と修正
- [`even-g2/app/README.md`](even-g2/app/README.md) — Even Hub プラグイン（Voice Harness G2）
- [`documents/even_g2_macless_deployment.md`](documents/even_g2_macless_deployment.md) — Macなし運用、非公開Beta配布、期限切れ表示の復旧
- [`documents/vad.md`](documents/vad.md) — Silero VAD / FFT フォールバックの仕様とチューニング
- [`documents/architecture.md`](documents/architecture.md) — アーキテクチャ詳細
- [`documents/ondevice_ai.md`](documents/ondevice_ai.md) — AIバックエンド（ローカル / Groq / OpenRouter）の準備・運用
- [`documents/groq_cloud.md`](documents/groq_cloud.md) — Cloud (Groq)
- [`documents/openrouter.md`](documents/openrouter.md) — OpenRouter LLM
- [`documents/opendroid-integration.md`](documents/opendroid-integration.md) — デジタルアシスタント統合
- [`documents/voice-harness-android-openrouter-plan.md`](documents/voice-harness-android-openrouter-plan.md) — OpenRouter・画面コンテキスト・対話 UI 実装計画（正本）
- [`documents/ondevice_gemma.md`](documents/ondevice_gemma.md) — Gemma 4 統合メモ
- [`documents/qwen_asr_encoder_issue.md`](documents/qwen_asr_encoder_issue.md) — Qwen3-ASR方式の調査・端末検証結果
