# Even G2 Macなし運用・非公開配布手順

## 結論

Voice HarnessのG2表示は、EvenHubへ登録したプラグインとAndroidアプリだけで動作する。
Mac上のViteサーバー（`:5173`）とQR/URLプロトタイプは開発時にだけ必要で、インストール後の
通常利用には不要。

実行時のデータ経路は次のとおり。

```text
Harness Node / Android UI
  -> Voice Harness Android foreground service
  -> http://127.0.0.1:8787（Android端末内だけ）
  -> Even Realities App内のVoice Harnessプラグイン
  -> Even G2
```

Android側のブリッジはloopbackにのみbindするため、LANやMacから本文を取得できない。
Even Hub WebViewからのアクセスには`app.json`のnetwork whitelistと、HTTPレスポンスのCORS
ヘッダーの両方が必要。

## パッケージ作成

```bash
cd even-g2/app
npm install
npm run pack
```

`out.ehpk`が生成される。SDK `0.0.14`の最低Even Appバージョンは`2.2.9`なので、
`app.json`にも`min_app_version: "2.2.9"`を明記する。pack時に次が表示されればよい。

```text
min_app_version 2.2.9  (SDK 0.0.14, --sdk-ver)
```

新しいビルドをアップロードするときは、`app.json`の`version`をSemVerで更新してからpackする。
`.ehpk`はAndroidで直接開いてインストールする形式ではない。

## EvenHub非公開配布

1. `https://hub.evenrealities.com/hub`を開く。
2. 初回は **Upload package** から`out.ehpk`をアップロードし、プロジェクトを作る。
3. **Testing group**で利用者のEven IDを追加し、招待を承認する。
4. **Builds**で対象ビルド右端の **Private** を押し、**Beta**を選ぶ。
5. **Promote to Beta**を実行する。
6. Even Realities Appの **My Plugins**からVoice Harnessをインストールまたは更新する。

重要なのは、アップロードだけではテスターへ配信されない点。PrivateビルドをBetaへ昇格すると、
EvenHubのbetaブランチにバージョンが割り当てられる。

## 「テスト版の有効期限が切れました」の対処

今回の発生原因は、テスターがActiveでもbetaブランチのバージョンが未設定だったこと。
ビルドはアップロード済みだったがPrivateのままで、Evenアプリが有効なBetaビルドを取得できなかった。

確認と復旧は次の順で行う。

1. Testing groupで対象Even IDが **Active**か確認する。
2. Buildsで対象バージョンが **Beta**か確認する。
3. Privateなら **Private → Beta → Promote to Beta**を実行する。
4. Even Realities Appを再起動し、My Pluginsから開き直す。
5. キャッシュが残る場合はプラグインを削除して再インストールする。

`edition: "202601"`は現行CLIが受理するEvenHub API editionであり、今回の期限切れ原因ではなかった。

## Android側の注意点

- 実際のEven Realities AppパッケージIDは`com.even.sg`。Android 11以降でアプリを直接開くには、
  `<queries>`と起動候補の両方に含める。
- Voice Harnessのforeground service起動時に`127.0.0.1:8787`のブリッジも起動する。
- G2プラグインが接続していない場合、AI返答はTTSへフォールバックする。
- 端末再起動後はAndroidサービスが自動復帰するが、Even HubプラグインはEven Realities Appから
  起動し直す必要がある場合がある。
- 開発用QR/Viteセッションを放置するとG2接続が不安定になることがある。実機確認後は
  プラグインのプロトタイプセッションとViteを終了する。

## リリース確認

- `npm run pack`が警告なしで成功する。
- `./gradlew testDebugUnitTest :app:assembleDebug`が成功する。
- Testing groupがActive、対象ビルドがBetaになっている。
- MacのVite停止、USB/ADB切断状態で、AI返答と読書ページ送りがG2で動く。
- プラグイン未起動時にTTSフォールバックする。
- Android再起動、画面消灯、Evenアプリのバックグラウンド復帰を確認する。
