# Voice Harness — Even G2

Even Realitiesの公式Even Hub SDKを使った、Voice Harness 公式の G2 表示プラグインです。

Even Hub 上の表示名は **Voice Harness**（`package_id`: `com.g150446.voiceharness.g2`）です。
以前の `hello-world` / `com.g150446.voiceharness.g2hello` を入れている場合は別アプリ扱いになるため、旧パッケージを削除してから入れ直してください。

G2には次を表示します。

- AI返答（ホームの「AI返答の出力先」で G2 を選択時）
- AndroidのVoice Harnessで抽出したKindle本文（リーダーモード）
- Harness Nodeの**シングルタップ**でG2本文を次ページへ進める（リーダーモード）
- G2のスワイプ上下で本文ページを前後移動
- G2のダブルタップでEven Hubの終了確認を表示
- 最終G2ページの次でKindleを1ページめくる（縦書き=左→右、横書き=右→左。LLMが判定）

## 必要条件

- Even G2とEven Realities Appをペアリングし、ファームウェアを更新済み
- Even HubのDeveloper Modeを有効化
- Node.js 20、22、またはそれ以降
- Voice Harness Androidアプリが同じ端末で起動していること
- 開発中はEven Hubネットワーク権限のため、QR URLを再読み込みすること

## ビルド

```bash
npm install
npm run build
```

配布用パッケージは次で`out.ehpk`として生成する。

```bash
npm run pack
```

## シミュレータ

2つのターミナルを使う。

```bash
npm run dev
```

```bash
npm run simulate
```

## 実機で表示

```bash
npm run dev
npx evenhub qr --url "http://<PCのLAN IPまたはTailscale IP>:5173"
```

Even Realities AppのEven Hub開発者メニューからQRコードを読み取る。  
スキャンできない場合は「QRコードをスキャンできない場合」から同じ URL を手入力して開始できる。

## グラス切断を防ぐ（重要）

Hub の **プロトタイプ起動（QR / URL）** や開発用 Vite を使い終わらずに放置すると、
Even Realities App と Even G2 の接続が不安定になることがある。日常利用の前に必ず片付ける。

### 安全な手順

1. Even アプリで G2 が **接続済み** であることを確認する
2. 必要なときだけ `npm run dev` を起動し、Hub で QR / URL からプラグインを開く
3. 表示テストが終わったら **プラグインを終了**する（G2 上のダブルタップ終了、または Hub からセッションを閉じる）
4. Mac 上の Vite を止める（`:5173` を LISTEN のままにしない）
5. もう一度 Even アプリで G2 接続を確認してから普段使いする

### やってはいけないこと

- プラグインや Vite を起動したままグラスを日常利用する
- 接続トラブル時に Even アプリ（`com.even.sg`）を **force-stop** する  
  （セッションが中途半端に落ちやすい。止めるならアプリ内の通常操作で終了する）
- 開発時以外に設定の **ターミナルモード** を ON のままにする

### 切断してしまったとき

1. Vite / プロトタイプセッションが残っていれば止める
2. Even アプリを通常起動し、グラスの再接続を試す
3. それでもダメならグラス電源・Even アプリの再起動（force-stop は最終手段）

Voice Harness Android の loopback ブリッジ（`:8787`）は、**プラグインが動いているときだけ**
ポーリングされる。プラグインを止めていれば、Voice Harness 本体の起動だけで G2 接続を
奪い続けることは通常ない。

恒久利用時は QR/URL プロトタイプより `.ehpk` インストールの方がセッション管理が安定しやすい。

## 動作フロー

### AI返答

1. Androidホームで「AI返答の出力先」を G2 にする。
2. このプラグインを Even Hub で起動したまま Voice Harness に返答させる。
3. Androidが loopback API に `mode=response` の本文を載せ、G2がページ分割して表示する。
4. プラグイン未接続なら Android は TTS へフォールバックする。

### リーダーモード

1. リーダーモードをONにする（ホームのトグル、音声「リーダーモード…」、または Harness Node の**ダブルタップ**）。  
   **Even G2 の Voice Harness プラグイン接続中のみ** ON できる。切替は G2 に `リーダーモード ON/OFF` と表示。  
   プラグイン終了で自動 OFF。
2. Kindleを前面にすると（起動ジェスチャーなしで）自動的に本文抽出が始まる。  
   ダブルタップ開始時は現在画面からも抽出する。ON中のダブルタップでリーダーモード終了。
3. AndroidがLLMで本文を抽出し、G2が実ピクセル幅に合わせてページ分割する（`mode=reading`）。
4. Harness Nodeの**シングルタップ**でG2内の次ページへ進み、末尾ではAccessibility のスワイプでKindleを次ページへ送る。  
   リーダーモード中のシングルタップは録音に使わない。  
   縦書きは左→右、横書きは右→左（抽出時に LLM が `writing_direction` を判定）。

自動開始は Kindle が前面になったとき 1 回。ページ送りのたびには再抽出しない。  
自動失敗時は TTS せずログのみ（手動起動時は従来どおり案内する）。

G2プラグインはAndroidのloopback API (`http://127.0.0.1:8787`) だけを読み取り、本文は永続化しない。

## 公式資料

- [Even Hub Quickstart](https://hub.evenrealities.com/docs/get-started/quickstart/index)
- [Your First App](https://hub.evenrealities.com/docs/get-started/quickstart/first-app)
- [Official starter templates](https://github.com/even-realities/evenhub-templates)
