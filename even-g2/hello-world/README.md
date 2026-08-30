# Voice Harness — Even G2 Hello World

Even Realitiesの公式Even Hub SDKを使った、G2対応のKindle読書パススルーです。

G2には次を表示します。

- AndroidのVoice Harnessで抽出したKindle本文をG2へ表示
- Harness NodeのダブルタップでG2本文を次ページへ進める
- G2のスワイプ上下で本文ページを前後移動
- G2のダブルタップでEven Hubの終了確認を表示

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
npx evenhub qr --url "http://<PCのLAN IP>:5173"
```

Even Realities AppのEven Hub開発者メニューからQRコードを読み取る。

## 動作フロー

1. Androidホーム画面で「パススルーモード」をONにする。
2. Kindleを表示した状態でHarness Nodeの起動ジェスチャーを行う。
3. AndroidがLLMで本文を抽出し、G2が実ピクセル幅に合わせてページ分割する。
4. Harness NodeのダブルタップでG2内の次ページへ進み、末尾ではAccessibility APIでKindleを次ページへ送る。

G2プラグインはAndroidのloopback API (`http://127.0.0.1:8787`) だけを読み取り、本文は永続化しない。

## 公式資料

- [Even Hub Quickstart](https://hub.evenrealities.com/docs/get-started/quickstart/index)
- [Your First App](https://hub.evenrealities.com/docs/get-started/quickstart/first-app)
- [Official starter templates](https://github.com/even-realities/evenhub-templates)
