# スマートグラスへのAI返答出力

> **現行ランタイムは Even Realities G2。**  
> Vuzix Z100 実装（`SmartGlassesOutputManager` / Ultralite SDK）は将来再配線用にコードと
> 依存を残しているが、サービス起動時には生成・呼び出ししない。  
> 以下の「現行（Even G2）」が正本。その後の「アーカイブ（Vuzix Z100）」は過去仕様。

## 概要（現行: Even G2）

AI返答の出力先をAndroid TTSとEven Realities G2から選択できる。入力、ASR、Chat、履歴保存は
共通で、最終的な返答の提示方法だけを切り替える。

```text
HarnessNode → BLE PCM → ASR → Chat → response
                                      ├─ PHONE_AUDIO   → Android TTS
                                      └─ SMART_GLASSES → EvenG2ReadingSession
                                                       → loopback :8787
                                                       → Even Hub plugin → G2
```

G2を選択しても返答は電話画面と履歴へ残る。Even Hubプラグインが bridge をポーリング中
（`isClientActive`）なら表示成功としてTTSを抑止し、未接続なら同じ返答をTTSへ戻す。

### UIと永続設定（G2）

| 選択 | 動作 |
|---|---|
| 音声 | Android TTS |
| G2 | Even Hub プラグインへ本文表示（`mode=response`）。失敗時TTS |

ホームの状態表示はプラグイン接続・返答表示中・リーダーモード中を示す。
**Even Realities Appを開く** ボタンでコンパニオン起動を試みる。

### 表示シーケンス（G2）

1. `VoiceProcessor` が最終返答を電話画面と履歴へ保存する
2. 出力先が `SMART_GLASSES` なら `EvenG2ReadingSession.displayResponse(text)`
3. セッションに `mode=response` と本文を載せ、最大約1.5秒プラグインのポーリングを待つ
4. 活性なら Started（TTSなし）。否则 clear して Failed → TTSフォールバック
5. 新しい録音開始 / 出力先を音声へ戻す / リーダーモードOFF で `clearDisplay()`

リーダーモードは `mode=reading`。G2プラグインがページ分割し、Harness Node の**シングルタップ**
（`singleTapCount` / `0x14`）で G2 内送りを行う。リーダーモード中の single は録音に使わない
（FW `0.0.94+` は notify-only、Android も RX を送らない）。末尾では
`/api/v1/reading/advance` 経由で Kindle を1ページめくる。LLM が `writing_direction` を判定し、
縦書きは左→右（`SWIPE_RIGHT`）、横書きは右→左（`SWIPE_LEFT`）。Z100フォールバックは行わない。

**ダブルタップ**はリーダーモードのトグル:

- OFF → ON（**G2 プラグイン接続時のみ**）+ 現在画面から本文抽出。G2 に `リーダーモード ON`
- ON → OFF（G2 に `リーダーモード OFF`、設定も永続 OFF）
- パイプライン処理中は従来どおり割り込み優先（モードは維持）
- プラグイン切断で自動 OFF（接続だけでは自動 ON しない）

リーダーモード ON のとき、Accessibility が Kindle 前面を検知すると起動ジェスチャーなしで自動抽出する
（デバウンスあり。既に READING セッション中や録音中はスキップ。自動失敗は silent）。
音声「リーダーモード…」やホームのトグルでも ON/OFF できる（ON は G2 接続必須）。
**ユーザー補助（`RingAccessibilityService`）が必須。** ホーム画面に有効/無効を表示し、
未許可時は設定へのショートカットボタンを出す。ページめくりも同サービス経由。

ブリッジAPI（loopback only）:

| Path | 用途 |
|---|---|
| `GET /api/v1/reading` | `enabled/active/mode/revision/bodyText/loading/error/doubleTapCount` |
| `POST /api/v1/reading/advance` | 読書モード末尾でのKindle次ページ要求 |
| `GET /api/v1/double-tap` | 診断用ダブルタップカウンタ |

プラグイン手順は [`even-g2/app/README.md`](../even-g2/app/README.md)
（Even Hub 表示名: Voice Harness、`package_id`: `com.g150446.voiceharness.g2`）。
開発用 QR/URL プロトタイプや Vite を使い終わったら必ず終了すること。放置すると
Even Realities App と G2 の接続が不安定になることがある（同 README「グラス切断を防ぐ」）。

フォールバックメッセージ: `G2に表示できなかったため音声で再生します`

---

# アーカイブ: Vuzix Z100へのAI返答出力

以下は Z100 を実行パスに載せていた頃の仕様。再有効化時の参照用。

## 概要（Z100 アーカイブ）

AI返答の出力先をAndroid TTSとVuzix Z100から選択できた。入力、ASR、Chat、履歴保存は
共通で、最終的な返答の提示方法だけを切り替える。

HarnessNodeで「リーダーモードに入って」などと指示した場合は、録音開始時に一時取得した
画面コンテキストをLLMへ渡し、要約・翻訳せず本文だけを抽出する。本文はZ100上で実際に
描画される行へ分割して先頭を表示し、HarnessNodeのダブルタップ（`0x12`）ごとに次ページを
表示する。抽出本文は会話や履歴へ保存せず、読書セッション中だけメモリに保持する。

ホーム画面の「リーダーモード」トグルをONにすると待機状態になり、電子書籍へ戻って
次のHarnessNode起動ジェスチャーを行うと、発話やVADを待たず取得済み画面から本文抽出を
開始する。設定は再起動後も保持する。OFFにすると現在のZ100表示、自動復元、スマホ画面の
リーダーバッジを即時終了する。音声コマンドから開始した場合もトグルをONへ同期する。

リーダーモードがONであれば、起動ジェスチャーを使わずHarnessNodeのダブルタップだけでも
現在のKindle画面を取得して本文抽出を開始できる。既存の読書セッションがある場合はグラス内の
次ページを表示し、最終ページではAccessibility ServiceでKindleを次ページへ進めて再抽出する。
シングルタップは診断ログのみで、この処理を起動しない。

> 注: 現行 Even G2 ランタイムでは ON に G2 プラグイン接続が必須。上は Z100 アーカイブ時の挙動。

```text
HarnessNode → BLE PCM → ASR → Chat → response
                                      ├─ PHONE_AUDIO   → Android TTS
                                      └─ SMART_GLASSES → Vuzix Connect → Z100
```

Z100を選択しても返答は電話画面と履歴へ残る。グラス表示に成功した場合だけTTSを抑止し、
表示できない場合は返答を失わないよう自動的にTTSへ戻す。

## 必要条件

- Android 12（API 31）以上
- Vuzix Connectアプリ
- Vuzix Connectでリンク・接続済みのVuzix Z100
- `com.vuzix:ultralite-sdk-android:1.9`
- Kindleの自動ページ送りを使う場合は、Android設定でVoice HarnessのAccessibility Serviceを有効化

Voice HarnessからZ100へ直接BLE接続しない。リンク、接続、他アプリとの制御調停は
Vuzix ConnectとUltralite SDKへ任せる。

## UIと永続設定

ホーム画面の **AI返答の出力先** スイッチで次を選ぶ。

| 選択 | 動作 |
|---|---|
| 音声 | 従来どおりAndroid TTSで読み上げる |
| Z100 | グラスへテキスト表示し、TTSは実行しない |

初期値は音声。選択はSharedPreferencesへ保存し、アプリまたは端末の再起動後も復元する。
Z100のSDK利用可否、リンク、接続、制御、表示中の状態は画面に表示する。

スイッチは未接続時にも変更できる。Z100が接続される前に出力先を準備でき、未接続のまま
返答が完成した場合は実行時フォールバックが働く。

## 表示シーケンス

1. `VoiceProcessor`が最終返答を電話画面と履歴へ保存する
2. 出力先がZ100なら、SDKのavailable、linked、connectedを確認する
3. 既存の表示を停止する
4. `requestControl()`でグラス制御を要求する
5. `controlledByMe`の成功通知を最大3秒待つ
6. `Layout.TEXT_BOTTOM_LEFT_ALIGN`を表示タイムアウト300秒で設定する
7. `sendText()`で返答全文を一度に表示する
8. 新しい録音、出力先変更、表示タイムアウト時に制御を解放する

リーダーモード（当時の読書パススルー）では通常の出力先設定にかかわらずZ100へ表示する。SDKの表示タイムアウトを
`0`（無期限）に設定し、通常返答用の12秒自動解放を適用しない。切断または制御喪失時も
描画行と現在位置はメモリ内に保持し、再接続後、他アプリがZ100を制御していない時点で
制御を再取得して現在ページを自動復元する。新しい録音開始、通常返答、出力先変更、
サービス終了で読書セッションを破棄する。

リーダー本文は `TextToImageSlicer` でAndroid側のフォントを行画像へ変換し、
`Layout.SCROLL` へ送る。これによりZ100内蔵テキストフォントの文字コードへ依存せず、
日本語と英数字が混在しても文字化けしない。文字サイズ35px、行スライス高48pxは維持し、
1ページの行数はZ100のキャンバス高を行スライス高で割って動的に算出する。固定文字数や
Android画面上の行数では分割しない。各描画時にステータスバー非表示を指定するため、
左上の時刻とバッテリーもリーダーモード中は表示しない。

読書セッション中は、Kindleなど他アプリの上にタッチを遮らない
`リーダー 1/N` のスマホ画面バッジを常時表示する。ダブルタップによるページ送り時は
ページ番号を更新し、セッション破棄時に消去する。録音開始時は従来の赤い録音アイコンを優先する。
この表示には録音アイコンと同じ `SYSTEM_ALERT_WINDOW` 権限が必要である。

取得対象は起動ジェスチャー時点の現在画面であり、ダブルタップでKindle等の端末画面自体を
ページ送りすることはない。`FLAG_SECURE`やアプリ実装によりVoiceInteraction APIが本文も
スクリーンショットも提供しない場合は、保護を迂回せず取得不可として案内する。

`requestControl()`の戻り値と`controlledByMe`のLiveData更新には時間差が生じる。制御通知を
待たずに表示APIを呼ぶと、要求が受理されていても制御未取得として扱われるため、必ず成功通知を
待ってから表示を始める。通常のAI返答は完成後に全文が確定しているため、スクロールAPIは
使わず、左揃えの静的テキストとして最初から全文を送る。リーダーモードだけは、日本語画像行と
実表示容量に基づくページ送りのためスクロールレイアウトを利用する。

## 中断と制御解放

Z100は複数アプリが制御を要求できるため、Voice Harnessは常時制御を保持しない。

- 新しいAI返答: 古い表示を全文で置き換える
- 表示成功から12秒後: 読み取り猶予のあと自動で表示消去と制御解放
  （次のHarnessNode録音とZ100 BLEが重ならないようにする）
- 新しいBLE録音: 表示中なら消去して制御解放。既にidleならZ100 BLE操作を省略
- 出力先を音声へ変更: 表示を停止し、制御を解放する
- プロファイル変更、BLE切断、サービス終了: 表示を停止し、制御を解放する
- 他アプリによる制御取得またはZ100切断: 内部の表示状態を破棄する

次の返答時には状態を再確認し、必要なら改めて制御を取得する。

## フォールバック

次の場合は `Z100に表示できなかったため音声で再生します` を電話画面へ表示し、同じ返答を
Android TTSで読み上げる。

- Vuzix Connectを利用できない
- Z100が未リンクまたは未接続
- `requestControl()`が拒否された
- 制御取得の成功通知が3秒以内に届かなかった
- 静的テキストのレイアウト設定または送信時に例外が発生した

Z100の一時障害で会話内容を失わないことを優先し、出力先の保存値は変更しない。次の返答で
再びZ100表示を試す。

## ログ確認

```bash
adb logcat -v threadtime -s SmartGlassesOutput:D VoiceProcessor:D \
  BleConnectionService:D AndroidRuntime:E
```

成功時：

```text
Displayed complete Z100 response (... chars)
Response routed to Z100
```

失敗時は具体的な未接続・制御・表示エラーに続き、TTSフォールバックのログが出る。

## 実機確認

1. Vuzix ConnectでZ100をリンク・接続する
2. Voice Harnessで出力先をZ100にする
3. HarnessNodeから短文を3回入力する
4. Z100に全文が一度に表示され、TTSが鳴らず、電話画面と履歴に返答が残ることを確認する
5. 長めの質問を1回行い、文字が自動スクロールしないことを確認する
6. Z100を切断して1回入力し、警告後にTTSへ戻ることを確認する
7. 出力先を音声へ戻し、従来の読み上げが動作することを確認する
8. リーダーモードで日本語が文字化けせず、左上の時刻・バッテリーが消えることを確認する
9. 1ページを超える本文でHarnessNodeをダブルタップし、次の画像行へ進むことを確認する

## Kindleのページ自動送り

Z100に表示中の本文が最後のページに到達した状態でHarnessNodeをダブルタップすると、前面が
Amazon Kindle（`com.amazon.kindle`）の場合だけ次の処理を行う。

1. Accessibility Serviceの画面ノードに`ACTION_SCROLL_FORWARD`があれば論理的な次ページ操作を実行
2. 画面が変化しなければ、抽出時にVLMが判定した`SWIPE_LEFT`または`SWIPE_RIGHT`を画面中央へ送信
3. Assistテキストとシステム領域を除いた画面指紋が変化するまで再取得を試行
4. 新しいKindle画面をLLMで本文抽出し、Z100の1ページ目から再表示

ページ方向が`UNKNOWN`、Kindle以外が前面、Accessibility Serviceが無効、または画面変化が確認
できない場合は推測操作をせず、現在のZ100表示を維持してスマホ側へエラーを通知する。操作中は
スマホのリーダーバッジが「次ページ取得中…」になる。短時間の連続ダブルタップは1回に制限する。
