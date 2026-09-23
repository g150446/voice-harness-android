# Claude Code / Codex のモード切替（2026-09-23）

> 「プランモードにして」が当たったり外れたりしていた理由と、回数を数えるのをやめた話。
> 現在の仕様は [`smart_glasses_output.md`](smart_glasses_output.md) と
> [`openclaw.md`](openclaw.md) が正。ここは「なぜそうなっているか」を残す。

---

## 1. 何が弱かったか

解釈 LLM が **⇧Tab を何回送るかを自分で決めて**いた。`HarborCommandTool.SYSTEM_APPENDIX` が
「現在のモードはターミナル文脈に書いてあるから、そこを読んで必要な回数だけ shift-tab を送れ。
読めなければ needs_clarification にしろ」と指示していた。

前提が3つとも成り立たない。

- 文脈の画面スナップショットは**解釈時点**のもの。確認タップまでにモードは動く。
- 循環に含まれるモードはセッション設定で変わる。`bypass permissions` が無い構成では
  固定回数が丸ごとずれる。
- `submitCommand` は計画した operation を**結果を見ずに**流すだけだった。`waitMs` の待ちは
  あっても、キーが効いたかも、着いた先が目的のモードかも確認していない。

読めなければ聞き返すので、成功しないだけでなく**断られる**ことも多かった。

## 2. Claude Code の構成

解釈が渡すのは**行き先だけ**。回数は誰も決めない。

```
action=mode (normal / accept_edits / plan / auto / dont_ask / bypass_permissions)
  → 画面を読む → 目的のモード？ → 違えば ⇧Tab を1回 → 待つ → また読む → …
```

| 段階 | 実体 |
|---|---|
| 語彙・プロンプト | `HarborCommandTool`（`HarborCommandAction.MODE` / `HarborStepAction.MODE`） |
| 計画 | `planHarborSubmit` → `HarborOperation.SetMode` |
| 実行 | `HarborMirrorController.applyClaudeMode` → `cycleToClaudeMode` |
| 画面の読み | `ClaudeCodeMode.kt` の `readClaudeCodeMode` |

`cycleToClaudeMode` は HTTP を持たない。`ClaudeModeCycler`（読む / 押す / 待つ）だけを受け取る
ので、ループの規則は `ClaudeCodeModeTest` でそのまま検証できる。

止まり方は3つ。

- **目的のモードに着いた** → 押した回数を添えて成功（「プランモードにしました（⇧Tab ×2）」）。
  0回なら「すでにプランモードです」。
- **一度見たモードに戻ってきた** → 循環が一周した。そのセッションにそのモードは無い。
  「このセッションでは権限スキップモードに切り替えられません（通常モードのままです）」。
- **画面からモードが読めない** → 後述。

`MAX_MODE_PRESSES` は保険であって終了条件ではない（終わらせるのは「一周した」の判定）。

### Codex は同じ操作ではない

Codex の Default / Plan は Claude Code の権限モードではない。Codex CLI は
Shift+Tab で Default と Plan を切り替え、Plan 中はフッターに `Plan mode` が出る。

そのため対象ワークスペースの agent が Codex の場合は、画面末尾を先に読んでから次のようにする。

- 既に目的のモードなら何も送らない
- Default ↔ Plan の変更が必要なときだけ Shift+Tab を送る
- Shift+Tab 後に画面を再取得し、目的のモードになったことを確認する
- Codex に自動編集・オート・自動拒否・権限スキップを要求された場合は、送信前に拒否する

実機のPlan表示は独立した行とは限らず、ステータス行末の
`GPT-5.6-Sol medium · … Plan mode` に出る。Codexの入力欄より**後ろだけ**をフッターとして
読み、そこに `Plan mode` があればPlan、無ければDefaultとする。これにより会話本文中の
「Plan mode」を現在モードと誤認しない。

Planから通常へ戻す場合も同じ処理で、現在がPlanであることを読んでから Shift+Tab を1回だけ
送り、次の画面で `Plan mode` が消えたことを確認する。

2026-09-23 の実機ログでは、Codex の通常画面を Claude Code 専用パーサーで読んでいたため
「画面から現在のモードを判定できません」で最初のキー送信前に止まっていた。タップ自体と
`action=mode` の解釈は成功していた。

## 3. 読めないときは1回も押さない

エージェントが動いている間は入力欄ごと画面から消えるので、モードは読めない。
そこで押すのは**当てずっぽうで循環を回すこと**なので、`readClaudeCodeMode` が null を返したら
⇧Tab を1回も送らずに中止する。`planHarborSubmit` が送れないキーを1つも送る前に弾くのと同じ方針。

押し始めた後に読めなくなった場合は、1回だけ読み直してから諦める（再描画が間に合っていない
だけのことがある）。諦めるときのメッセージには**そこまでに押した回数**を入れる。
途中で止まるとユーザーは中途半端なモードに立たされるので、どこにいるか言えないと困る。

## 4. フッターの読み方

`GET /v1/workspaces/{id}/screen` の**末尾 12 行だけ**を下から見る。さらに、⏸ / ⏵⏵ などの
非文字を落とした後に**行頭がモード文言で始まる行**しか採らない。

エージェント自身が「plan mode on のときは書き込みが止まります」と喋るので、画面全体を
`contains` で探すと**モードの話をしているのを、そのモードにいると読んでしまう**。
行頭判定なら、`●` や `⎿` を落とした先が日本語や別の単語で始まるため当たらない。

モード文言が1つも無くても、入力欄が出ている証拠（`? for shortcuts` / `shift+tab to cycle`）が
あれば通常モードとみなす。通常モードはフッターに何も出さないため。

### 文言は実物から取った

Claude Code 2.1.280 のバイナリが `plan mode on` / `accept edits on` / `auto mode on` を
そのまま持っている。設計時に想定していた「plan / accept edits / bypass の3つ」は誤りで、
実際は `default / accept edits / plan / auto / don't ask / bypass permissions` の6種だった。
`auto` を知らないままだと、auto モードにいるセッションは「判定不能」になって
モード変更を断り続ける。

```bash
strings -n 3 "$(readlink "$(which claude)")" | grep -x "plan mode on\|accept edits on\|auto mode on"
```

**Claude Code が文言を変えたら直すのは `ClaudeCodeMode.kt` の `MODE_MARKERS` だけ**。
表に無いモードは「判定不能」に落ちるので、黙って循環を回し過ぎることはない。

## 5. 素通しのまま残したもの

- **`key=shift-tab`**: 「Shift Tab を押して」と明示的に言われた1回押し。モード指定として
  解釈し直したりしない。
- **キー欄の ⇧Tab ボタン**（`MainActivity` の Harbor 画面）: 従来どおり1回。
- **`/model` の手順**: `/model` 貼付 → ↑↓ → Enter。画面確認は入れていない。今回の範囲外。

## 6. 実機検証（2026-09-23）

motorola razr 50s / Claude Code 2.1.280 で動作確認済み。

ログは `adb logcat -s HarborApiClient:I` に出る。`key http=200 key=shift-tab` が必要な回数だけ
並び、最後に `mode target=plan reached=plan presses=2` が1行出る。

このときのインストールは**テザリング経由のローカル無線 adb**（`adb connect <ゲートウェイ IP>:5555`、
`scripts/adb-wireless.sh` の候補2番）で、144MB の debug APK が **29秒**で入った。
README が言う「APK は USB 推奨」は tailnet 経由の話で、リンクローカルの無線には当てはまらない。
