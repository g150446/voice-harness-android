# Vuzix Z100へのAI返答出力

## 概要

AI返答の出力先をAndroid TTSとVuzix Z100から選択できる。入力、ASR、Chat、履歴保存は
共通で、最終的な返答の提示方法だけを切り替える。

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

`requestControl()`の戻り値と`controlledByMe`のLiveData更新には時間差が生じる。制御通知を
待たずに表示APIを呼ぶと、要求が受理されていても制御未取得として扱われるため、必ず成功通知を
待ってから表示を始める。AI返答は完成後に全文が確定しているため、スクロールAPIは使わず、
左揃えの静的テキストとして最初から全文を送る。

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
