# Harbor 確認画面と意図解析の一本化（2026-09-16 解決済み）

> 「AI が意図を確認する画面でシングルタップしても指示が実行されない」から始まった一連の調査と
> 修正の記録。**解決済み**。現在の仕様は [`smart_glasses_output.md`](smart_glasses_output.md) と
> [`architecture.md`](architecture.md) が正。ここは「なぜそうなっているか」を残す。

---

## 1. 最終的な構成

```
STT → Android の LLM（harbor_command tool）→ G2 に確認表示 → タップで確定 → そのまま実行
```

確認画面より後に**解釈は一切挟まない**。確定した action をエンドポイントへ直接投げる。

| action | エンドポイント |
|---|---|
| `INSTRUCTION` | `POST /v1/workspaces/{id}/instruction` |
| `KEY` | `POST /v1/workspaces/{id}/key` |
| `SWITCH_WORKSPACE` | `POST /v1/workspaces/{id}/activate` |

> 2026-09-23 追記: 「音声でワークスペースが切り替わらない」という報告があったが、
> `activate` は成功していて、追従していなかったのはアプリの画面だった。経緯は
> [`harbor_workspace_switch.md`](harbor_workspace_switch.md) に分けてある。

> 2026-09-22 追記: 「Android の LLM」は **OpenClaw（ワークスペース単位のセッション）→
> Groq / OpenRouter → 決定論的 fallback** の順に変わった。どのエンジンを使っても
> 「解釈は確認画面の前に 1 回だけ」「実行はアプリが直接」は変わらない。OpenClaw は 15 秒で
> 打ち切って次のエンジンへ落とす — ここで挙げた 6.4 秒の分類器を却下した理由がそのまま効く。
> また `KEY` は Enter 以外も送れるようになり、`steps[]` で複数手順を 1 回の確認にまとめられる。

> 2026-09-25 追記: 解釈後の意図確認コメントはAndroid TTSでも読み上げる。読み上げるのは
> AIの確認内容だけで、画面上の「シングルタップで実行 / ダブルタップで取り消す」は含めない。
> TTS中も確認pendingがタップを所有するため、読み終わりを待たずに実行・取消できる。
> シングルタップを受け付けて実行するときは「実行します」、ダブルタップで取り消すときは
> 「キャンセルします」と読み上げる。実行処理は読み上げ完了を待たず同時に開始する。

> 2026-09-25 追記: 解釈待ちの間、Android画面は「文字起こし中」のまま止まり、確認カードの
> 形をした「解析中…」（有効な実行ボタン付き）が出ていて、AIが指示を確認していることが
> 分からなかった。`harborInterpreting` フラグで「AIが指示を確認中…」カードを出すようにした。
> G2の「解析中…」と、待機中のタップをキューして後で実行する挙動は変えていない。
> 初版は実機で表示されなかった。G2未接続だとダブルタップは指示録音（COMMAND）ではなく
> AI対話録音になり、解釈は `presentHarborConfirmSuspend` ではなくAI対話の `harbor_command`
> tool 経由（ログは `Chat latency … tools=1`、`Harbor intent:` は出ない）だったため、フラグが
> 一度も立っていなかった。加えてホームのカードが `response.isNotEmpty()` の内側にあり、
> 解釈中は response を空にするので描画されなかった。両方を直した。

タップの割り当ては G2 の表示文言に一致させる。

| 画面 | single | double |
|---|---|---|
| 確認（`シングルタップで実行 / ダブルタップで取り消す`） | 「実行します」→実行 | 「キャンセルします」→取り消し |
| 言い直し待ち（`ダブルタップで言い直す`） | 質問を再表示 | 録音し直し |

---

## 2. 直した4つの不具合

### 2.1 Harbor 側: `unsupported` 応答がパースできず落ちていた

`ModelVoiceIntent.target` が非 `Option` の `String` だった。ワークスペース切り替え以外の
**すべての**要求でモデルは `{"intent":"unsupported"}` を返して `target` を省くため、
serde が `missing field` で失敗し、`POST /v1/voice/intent` が `unsupported` ではなく
`model_unavailable`（「応答を解析できません」）を返していた。

同一リクエストを 6 回投げて 6/6 で `target` 欠落を確認。`#[serde(default)]` を付けて解決。
（`terminal-harbor` 側の修正・配置済み）

### 2.2 アプリ側: 結果メッセージが1秒で消えていた

`executeHarborConfirm` が `clearHarborConfirm` で `pendingHarborCommand` を null にした時点で
Harbor ミラーのポーリング（1秒周期）が再開し、結果メッセージを即座に上書きしていた。
**成功していても「何も起きなかった」ように見える**。

`harborSubmitInFlight` と `HARBOR_RESULT_HOLD_MS`（4秒）を追加し、結果が読めるまで
ミラーを止めるようにした。§2.1 のエラーメッセージが誰にも見えなかったのもこれが原因。

### 2.3 アプリ側: 無言で終わる分岐があった

`executeHarborConfirm` は pending が null のときと `awaitingClarification` のとき、
ログ1行だけで**UIに何も出さずに return** していた。録音も始まらないので、
「タップがBLEで届いていない」のと見分けがつかず、原因の切り分けを不可能にしていた。

`harborConfirmOutcome`（`EXPIRED` / `NEEDS_CLARIFICATION` / `SUBMIT`）を純粋関数として切り出し、
**どの分岐でも必ずG2に何か出す**ようにした。

### 2.4 両側: 二重解釈が承認済みの指示を書き換えていた

Harbor の `handle_voice_intent` は、分類器が `unsupported` を返しても生の transcript を
ワークスペース名にファジーマッチさせて**切り替えを実行**していた。Android 側のLLMが短い指示を
展開する際にコンテキストからリポジトリ名を書き込むため、

```
STT : 整理してコミットして、プッシュもして。
送信: はい。両リポジトリ（voice-harness-even-g2 と terminal-harbor）を整理して…
```

が「voice-harness-even-g2 に切り替えました」に化けた。**ユーザーが承認した指示が破棄される。**

Harbor 側は2段階で絞り込んだ（配置済み）:

- 分類器の明示的な `unsupported` を最終判断とし、生transcript一致で上書きしない
- 残る `Err`（モデル障害）分岐向けに `direct_switch_candidate` を追加。一致したフィールドを
  超える文字数が `VOICE_DIRECT_MAX_EXTRA_CHARS`（12）以内の発話だけを切り替えとみなす。
  「terminal-harbor に移動」は通り、名前に言及しただけの長い指示は落ちる

そのうえで**アプリ側は `/v1/voice/intent` を使うのをやめた**。これが §1 の構成。

---

## 3. なぜ LLM を1回にしたか

Harbor 側の分類器ができるのは `switch_workspace` か否かの判定だけで、アプリが必要とする
`INSTRUCTION` / `KEY` の区別はできない。一方アプリ側は確認UIを持っているため、
**確認の後ろに別の解釈器を置くと、承認した内容を覆せてしまう**。§2.1 と §2.4 はどちらもそれ。

実測コストも大きかった。承認タップ `18:51:28.487` → `voice-intent` 応答 `18:51:34.848` で、
Harbor 側の解析だけで **6.4秒**。ユーザーは既に承認しているのに待たされていた。

切り替え判定に必要な情報は `HarborInterpretContext.availableWorkspaces` として
アプリ側のプロンプトに渡している（`fetchInterpretContext` は元から `listWorkspaces` を
呼んでいるので追加コストなし）。

### プロンプトは実測で詰めた

最初の版では実モデル（`~deepseek/deepseek-v4-flash-latest`）が
「voice-harness-even-g2 と terminal-harbor の両方を整理してコミットして」を
`switch_workspace` と判定し、**Harbor側と同じ誤りをアプリ側で再現**した。
「切り替えが要求のすべてで、着いた後に何もすることがない場合だけ switch」
「作業依頼ならワークスペース名を含んでいても instruction」と、誤爆した実例そのものを
反例として `HarborCommandTool.SYSTEM_APPENDIX` に入れて解決した。

---

## 4. 調査で分かった運用上の注意

- **logcat のリングバッファが既定 256 KiB** しかなく、数分で流れる。再現ログを取るなら
  `adb logcat -G 16M` で拡張してから。初回調査で「BLEログが1行もない」と見えたのはこれと、
  `-v time` 形式が `VoiceProcessor(pid):` で `VoiceProcessor:` に grep がマッチしないことの合わせ技。
  **BLE断ではなかった。**
- 送信された実テキストは端末の `voice_history_prefs.xml`（`history_json`）から取れる。
  ログに出ない文字列の特定に有効。
- 159MB の debug APK は **USB必須**。Tailscale 経由は10分でタイムアウトする。
  無線 adb はログ取得・設定確認には十分使える（`scripts/adb-wireless.sh`）。
