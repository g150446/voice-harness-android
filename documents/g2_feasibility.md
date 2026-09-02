# Even Realities G2 への移植可否検討

> **実装ステータス（2026-09）:** AI返答表示と読書パススルーのランタイムは Even G2
> （`EvenG2ReadingSession` + loopback bridge + Even Hub プラグイン）へ切り替え済み。
> Z100 の `SmartGlassesOutputManager` は実行パスから外し、コード/SDK依存のみ残置。

> 調査日: 2026-08-16  
> 対象: 現行 Voice Harness の Vuzix Z100 出力機能を、Even Realities G2 公開SDKで同等実現できるか

## 結論

**現行Z100で実現している機能そのものは G2 でも実現可能**だが、**同じ「ネイティブ1 APKへのSDK差し替え」では不可能**である。G2 は実行モデルが WebView サンドボックス（Even Hub プラグイン）のため、表示専用プラグインと native アプリ間ブリッジへの再設計が必要になる。

| 問い | 答え |
|------|------|
| 同じUX（AI返答をグラス表示、失敗時TTS）は可能か？ | **はい（条件付き）** |
| Z100 SDK の置き換えだけで済むか？ | **いいえ** |
| 追加で必要なもの | Even Hub プラグイン + native↔plugin ブリッジ |
| 現行で未使用の高機能 Canvas 等は必要か？ | **不要** |
| 実装難易度 | API面は低い / **実行モデルとBG・BLEが本体** |

技術的に足りないのは表示APIではなく、**ForegroundService から G2 へ確実にテキストを届ける経路**である。

---

## 1. 現行Z100実装の実態

### 1.1 役割

Z100 は **AI返答の表示先（TTSの代替）** としてのみ使われている。入力・ASR・Chat・履歴はすべて電話側で共通。

```text
HarnessNode → BLE PCM → ASR → Chat → response
                                      ├─ PHONE_AUDIO   → Android TTS
                                      └─ SMART_GLASSES → Vuzix Connect → Z100
```

詳細仕様: [`smart_glasses_output.md`](smart_glasses_output.md)

### 1.2 コード上の接点

| パス | 役割 |
|------|------|
| `SmartGlassesOutputManager.kt` | **唯一の** Ultralite SDK クライアント |
| `ResponseOutput.kt` | 出力先判定とTTSフォールバック |
| `VoiceProcessor.kt` | `displayResponse` / `stopDisplay` の呼び出し |
| `BleConnectionService.kt` | Manager の生成・破棄、状態の公開 |
| `MainActivity.kt` | 音声⇔Z100 スイッチ、状態表示、Vuzix Connect起動 |
| `app/build.gradle.kts` | `com.vuzix:ultralite-sdk-android:1.9` |

### 1.3 実際に使っている Ultralite API

| API | 用途 |
|-----|------|
| `UltraliteSDK.get` | シングルトン取得 |
| `available` / `linked` / `connected` / `controlledByMe` | 状態監視（LiveData） |
| `name` | 端末名のUI表示 |
| `requestControl` / `releaseControl` | 排他制御の取得・解放 |
| `setLayout(TEXT_BOTTOM_LEFT_ALIGN, 300, …)` | 静的テキストレイアウト |
| `sendText(text)` | 返答全文を一度に表示 |
| `setLayout(DEFAULT)` | 表示クリア |

定数（アプリ側）:

| 定数 | 値 | 意味 |
|------|-----|------|
| `DISPLAY_TIMEOUT_SECONDS` | 300 | SDK表示タイムアウト |
| `CONTROL_CONFIRMATION_TIMEOUT_MS` | 3_000 | 制御取得待ち |
| `DISPLAY_HOLD_BEFORE_RELEASE_MS` | 12_000 | 読み取り猶予後に自動解放 |

### 1.4 明示的に未使用のAPI

現行アプリは次を**使っていない**（G2移植時も必須ではない）:

- Canvas（Bitmap任意配置、画像/テキストオブジェクト）
- フォント変更（`setFont`）
- 文字揃え（CENTER/RIGHT）
- アニメーション
- `sendNotification`
- `screenOn` / `screenOff`
- タップ / EventListener
- スクロールテキストAPI
- マイク / IMU（Z100公開SDKにも相当APIは見当たらない）

### 1.5 エンドユーザー機能との対応

| 機能 | Z100依存 |
|------|----------|
| AI返答をグラスへ全文表示 | **はい（任意出力先）** |
| 表示成功時はTTS抑止 | はい |
| 失敗時TTSフォールバック | はい（劣化パス） |
| 出力先設定の永続化 | はい（SharedPreferences） |
| 接続状態の電話UI表示 | はい |
| 音声録音 / ジェスチャ | **いいえ**（HarnessNode） |
| ASR / LLM | **いいえ**（電話） |
| 履歴 / リマインダ | **いいえ**（電話） |

---

## 2. Z100 と G2 の公開SDK比較（一般論）

ユーザー提供の調査および Even Hub 公式ドキュメント（2026-06〜07時点）に基づく。

| 観点 | Vuzix Z100 | Even Realities G2 |
|------|------------|-------------------|
| 開発形態 | **ネイティブ Android AAR** | **WebView 内の JS/TS アプリ** |
| 任意 Android API | **◎** | △ SDKブリッジ経由のみ |
| テキスト表示 | ◎ | ◎（左上固定、フォント変更不可） |
| 画像表示 | **◎ 自由度高い** | ○ コンテナ制約あり |
| フォント size/style | **◎** | **不可** |
| 左/中央/右揃え | **◎** | **不可** |
| アニメーション | **◎** | **不可** |
| 任意座標 Bitmap | **◎** | △ ImageContainer のみ / no arbitrary pixel drawing |
| 画面 ON/OFF | **◎** | 公開APIなし |
| 通知API | **◎** | 同等APIなし |
| タッチ | △ 1/2/3 tap | **◎ press/double/swipe** |
| IMU | 公開SDKに見当たらず | **◎** |
| メガネマイク | 公開SDKに見当たらず | **◎ 4-mic PCM** |
| R1リング | ― | **◎** |

参考:

- [Vuzix Ultralite SDK Android](https://github.com/Vuzix/ultralite-sdk-android)
- [Even Hub Architecture](https://hub.evenrealities.com/docs/get-started/architecture)
- [Even Hub Display](https://hub.evenrealities.com/docs/build/display)
- [Even Hub Device APIs](https://hub.evenrealities.com/docs/build/device-apis)
- [Even Hub Background](https://hub.evenrealities.com/docs/build/background-lifecycle)

**注意:** GitHub の `EvenDemoApp` は G1 向け低レベルBLE説明を含み、G2公式推奨モデルは `@evenrealities/even_hub_sdk` + Even Hub である。G2用正式ネイティブAndroid SDKとはみなさない。

---

## 3. 現行機能単位の G2 可否

| 現行Z100機能 | G2公開SDKでの対応 | 判定 |
|--------------|-------------------|------|
| AI返答をテキスト表示 | `TextContainer` + `textContainerUpgrade`（最大2000文字、左上固定） | **可** |
| 表示消去 | 空文字更新またはページ再構築 | **可** |
| 接続状態のUI表示 | `getDeviceInfo` / `onDeviceStatusChanged` | **可（表現は変わる）** |
| 他アプリとの排他制御 | `requestControl` 相当なし。Even側ページlifecycle | **△ 別設計** |
| 12秒後クリア | プラグイン側タイマーで可 | **可** |
| TTSフォールバック | 電話APK側ロジックはそのまま | **可** |
| 同一APKからネイティブ呼び出し | G2は Even App WebView 内JSのみ | **不可** |
| バックグラウンド中の表示トリガ | Android WebView は suspend され得る | **要対策（最大リスク）** |
| HarnessNode BLE共存 | G2も電話BLE経由 | **要再検証** |

表示APIの不足（フォント・中央揃え・アニメ等）は、**現行 Voice Harness のZ100利用範囲には不要**。

### 3.1 G2 テキスト表示の制約（仕様）

- Canvas: 576×288、4-bit greyscale
- テキスト: 左揃え・上揃え固定、フォント選択/サイズ/太字なし
- 文字数上限:
  - `createStartUpPageContainer` / `rebuildPageContainer`: 1,000
  - `textContainerUpgrade`: 2,000
- 全画面テキストコンテナの目安: 約400–500文字（超過時はファームウェアがスクロール）
- 改行 `\n` 可、Unicodeはファームウェアフォントに含まれるグリフのみ

Z100の「全文を一度に静的表示・自動スクロールなし」とは、長文時の体感が異なる可能性がある。

---

## 4. アーキテクチャ差分

### 4.1 現行（Z100）

```text
┌─────────────────────┐     BLE GATT      ┌──────────────────────┐
│  HarnessNode        │ ────────────────▶ │  Phone :app          │
│  (録音・ジェスチャ)   │                   │  BleConnectionService│
└─────────────────────┘                   │  VoiceProcessor      │
                                          │    ASR → Chat → out  │
┌─────────────────────┐  Vuzix Connect    │         ├─ TTS       │
│  Vuzix Z100         │ ◀─ UltraliteSDK ─│         └─ SmartGlasses│
│  (表示のみ)          │                   └──────────────────────┘
└─────────────────────┘
```

- 同一APK内で ForegroundService から直接表示APIを呼べる
- リンク/接続/制御調停は Vuzix Connect + Ultralite SDK に委任

### 4.2 G2 公開モデル

```text
Even Hub Cloud
      │
Phone: Even Realities App (Flutter) + WebView プラグイン
      │ Bluetooth
Even G2 (表示 + 入力)
```

- プラグインは HTML/CSS/JS(TS)
- Android API（FGS、NotificationListener、任意BLE等）をプラグインから直接は使えない
- 権限も Even Hub 定義（`network`, `location`, `g2-microphone` 等）に限定

### 4.3 移植時に必要な形

```text
HarnessNode ──BLE──▶ Voice Harness (native FGS)
                         │ ASR → Chat → presentResponse
                         │
                         │ localhost HTTP / WebSocket 等
                         │ （返答テキスト + 表示/消去コマンド）
                         ▼
                   Even Hub プラグイン (JS)
                         │ textContainerUpgrade
                         ▼
                        G2
```

「G2 SDKからAndroid APIを直接呼ぶ」のではなく、**native companion と Even Hub プラグインの協調**になる。

---

## 5. 推奨実現案

### 5.1 Even Hub プラグイン

1. 起動時に全画面相当の `TextContainer` を1つ作成（`isEventCapture: 1`）
2. native から受け取った文字列を `textContainerUpgrade` で表示
3. 表示成功から約12秒後に空文字更新または消去（現行の読み取り猶予を踏襲）
4. （任意）接続/装着/電池を `getDeviceInfo` / `onDeviceStatusChanged` で監視し native へ返す
5. （任意）長文は分割表示やスワイプページング（ファームウェアスクロールに任せる案もあり）

### 5.2 Voice Harness（native）

1. `SmartGlassesOutputManager` を **G2アダプタ**に差し替え  
   公開契約は維持: `displayResponse` / `stopDisplay` / `close` / `state`
2. ローカルサーバ（または同等IPC）でプラグインと通信
3. 失敗時は現行どおり TTS フォールバック（`decideResponseDelivery`）
4. UIラベルを Z100 → G2、コンパニオン起動を Even Realities App 向けに変更

### 5.3 変更がほぼ不要なもの

- ASR / LLM / 履歴 / リマインダ / HarnessNode BLE スタック
- `ResponseOutputTarget` と TTSフォールバック方針
- 「音声 ⇔ グラス」スイッチのUIパターン

### 5.4 インターフェース案（概念）

```kotlin
// 現行 SmartGlassesOutputManager と同等の契約を維持
interface SmartGlassesOutput {
    val state: StateFlow<SmartGlassesState>
    suspend fun displayResponse(text: String): SmartGlassesDisplayResult
    fun stopDisplay()
    fun close()
}
```

Z100実装とG2実装を差し替え可能にし、ルーティング層（`VoiceProcessor` / `ResponseOutput`）は触らない。

---

## 6. ブロッカーとリスク

### 6.1 バックグラウンド（最重要）

Voice Harness は FGS で返答を出すが、G2表示は **Even App の WebView** 経由。

公式ドキュメント上、Android では Even App がバックグラウンドに入ると Chromium WebView が **suspend され得る**。その場合:

- 開いていた WebSocket が切れる
- 表示コマンドが届かない
- プラグインの in-memory 状態が失われる可能性

対策候補:

1. 表示直前に Even App を前面化（UXは悪化）
2. プラグイン常駐 + 再接続時に未表示テキストを再送
3. 失敗したら即 TTS（現行フォールバックを強化・タイムアウト短縮）
4. 実機で「画面OFF / Even App非表示 / ロック」条件を必ず試験

### 6.2 ブリッジのネットワーク制約

Even Hub プラグインの `fetch` は次の両方が必要:

1. `app.json` の `network` whitelist
2. サーバ側 CORS

`http://127.0.0.1` は公式にもローカル開発向けの記述がある。本番配布時に localhost ブリッジが許容されるか、別ホスト/方式が必要かを要確認。

### 6.3 文字量・レイアウト

- 更新上限 2,000 文字、一画面目安 400–500 文字
- 左上固定・フォント固定
- 長文はファームウェアがスクロール（Z100現行はスクロールAPIを意図的に使わず静的全文）

受け入れ基準で「長文の見え方」を明示すること。

### 6.4 BLE共存（HarnessNode）

Z100 でも確認済みの問題（[`ble_audio_reliability.md`](ble_audio_reliability.md)）:

- グラス表示直後、電話BTの短い接続間隔と HarnessNode PCM Notify が競合し得る
- 症状: `wall >> pcm`（シーケンスギャップ無しで受信時間不足）

現行の緩和:

- 表示成功から約12秒で制御解放
- 録音開始時、既に idle ならグラス向けBLE操作を省略

G2 でも同様の方針を踏襲し、条件別受け入れ試験が必要。

### 6.5 運用・UX

- Even Realities App のインストールとG2ペアリングが必須
- プラグインの配布・更新が Voice Harness APK と別系統
- ユーザ手順が「Vuzix Connect」から「Even App + プラグイン」に変わる
- 状態モデル（available/linked/connected/controlled）の意味が1対1対応しない

---

## 7. 現行機能に不要な「Z100 ○ / G2 ×」

一般比較で差が大きい項目のうち、**現行 Voice Harness では未使用**のため移植ブロッカーにならないもの:

1. ネイティブAndroid SDKとして同一APKに組み込める ← **実行モデルとしては最大差だが、ブリッジで迂回する**
2. フォント family/style/size
3. LEFT/CENTER/RIGHT 揃え
4. Canvas アニメーション
5. Android Bitmap の任意座標配置
6. screenOn/off・専用 notification API

逆に G2 が強いマイク/IMU/スワイプ/R1 は、**現行のZ100出力機能の再現には不要**。将来拡張（グラス入力やグラスマイク）ではG2の方が有利。

---

## 8. 移植時の最小要件マップ

| アプリ機能 | Z100 API（現行） | G2 で必要なもの |
|------------|------------------|-----------------|
| コンパニオン/SDK利用可否 | `isAvailable` | Even App 起動可否 + プラグイン生存 |
| ペアリング | `isLinked` | Even App 側のG2リンク状態 |
| 接続 | `isConnected` | `getDeviceInfo` / ステータスイベント |
| 端末名 | `sdk.name` | device info（任意） |
| 排他 | `requestControl` / `releaseControl` | ページ表示中のセッション設計 |
| 全文表示 | `setLayout` + `sendText` | `textContainerUpgrade` |
| クリア | `setLayout(DEFAULT)` | 空更新 / rebuild |
| 自動dismiss | アプリ12秒タイマ | 同様（pluginまたはnative） |
| 失敗時TTS | アプリロジック | 同一 |
| コンパニオン起動UI | `com.vuzix.connect` | Even Realities App パッケージ |
| HarnessNode非競合 | idle skip + timed release | 同方針 + 再試験 |

**現行UXを保つ最小G2サーフェス:** 接続状態 + 複数行/静的テキスト表示 + クリア + （可能なら）セッション制御。

---

## 9. 実装ステップ案（未着手）

1. **契約抽出:** `SmartGlassesOutput` インターフェース化、Z100実装を実装クラスへ
2. **Even Hub 最小プラグイン:** 全画面テキスト1コンテナ、手動で文字列更新できるところまで
3. **ブリッジPoC:** native localhost サーバ ↔ プラグイン `fetch`/WebSocket
4. **G2アダプタ:** `displayResponse` / `stopDisplay` / state をブリッジ経由で実装
5. **フォールバック結合:** 未接続・タイムアウト・プラグイン死亡時にTTS
6. **BG試験:** 画面OFF、Even App非表示、5分ロック後
7. **BLE共存試験:** Z100時と同様の条件表（切断/TTSのみ/表示直後/解放後60–90秒）
8. **UI/ドキュメント:** ラベル、README、本ドキュメントの「実装済み」節への更新

---

## 10. 受け入れ試験（G2向け草案）

Z100時の試験（`smart_glasses_output.md` / `ble_audio_reliability.md`）を踏襲し、G2固有条件を追加する。

### 表示・フォールバック

1. Even App でG2を接続し、プラグインを起動
2. Voice Harness で出力先をグラスにする
3. 短文を3回入力 → G2に表示、TTSなし、電話画面と履歴に残る
4. 長文1回 → 文字量・スクロール挙動が許容範囲か
5. G2切断またはプラグイン停止 → 警告後TTS
6. 出力先を音声に戻す → 従来TTS

### ライフサイクル

7. 表示中に新録音開始 → 表示クリア、PCM欠落が許容内か
8. 表示成功から12秒後 → 自動クリア
9. 電話画面OFF / Even Appをバックグラウンドにした状態で返答完了 → 表示またはTTSフォールバックが仕様通りか
10. 5分ロック後に再開 → ブリッジ再接続と再表示/フォールバック

### BLE共存

11. G2切断
12. G2接続・出力はTTSのみ
13. G2へ返答表示した直後
14. 表示・解放から60〜90秒後  

各条件で PCM 完全性（`wall ≈ pcm`、gap無し）を記録する。

---

## 11. 総合判定

| 項目 | 評価 |
|------|------|
| 機能パリティ（現行Z100出力） | **達成可能** |
| 工数の本体 | 表示APIではなく **2プロセス連携とBG信頼性** |
| リスク | Android WebView suspend、localhost/CORS、BLE共存、長文体感 |
| 推奨方針 | native は現行パイプライン維持 + 薄いG2アダプタ / 表示は Even Hub プラグイン |
| 非推奨 | EvenDemoApp をG2正式native SDKとみなすこと / 全処理をWebViewへ移植すること |

**判定: 実現可能。ただし「SDK差し替え」ではなく「表示専用 Even Hub プラグイン + ブリッジ」への再設計。**

---

## 関連ドキュメント

- [`smart_glasses_output.md`](smart_glasses_output.md) — 現行Z100出力仕様
- [`ble_audio_reliability.md`](ble_audio_reliability.md) — HarnessNode PCM とグラスBLE共存
- [`architecture.md`](architecture.md) — システム全体構成
