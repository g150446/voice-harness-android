> **一時ファイル — 作業中の計画です**
>
> 作成 2026-09-01。Step A–D が実装され、確定した内容が
> [`gesture_false_trigger.md`](gesture_false_trigger.md) と
> `harness-node/docs/flex_pronation_gesture.md` に反映された時点で
> **このファイルは削除する。**
> 恒久的な仕様・調査結果はそちらを参照すること。
> ここに書かれた数値は 2026-09-01 時点の暫定であり、標本も小さい。

# 発話ゲートの言語制限、ダブルタップ中断、解析窓の是正

## Context

2026-09-01 の外来で誤発火が2件（11:50 / 11:55、ユーザーがラベル済み）。
11:55 は既存ゲートが `SUPPRESS_NON_CJK` で止めたが、**11:50 は素通りし、
中国語の応答が生成されて診察室で TTS が読み上げられた**:

> 這 因為怎麼吃掉 → 請問您指的是哪種食物呢？請告訴我名稱，我來為您說明吃法。

`UtteranceIntentGate` の CJK 判定は漢字（`一-鿿`）を含むため、中国語は
「CJKあり」と判定されて非CJK抑制をすり抜ける。履歴100件で「漢字あり・かな無し」は
**この1件のみ**。

あわせて、誤発火に気づいた時に**その場で止める手段が無い**ことも課題として挙がった。

### 調査結果: 掌上静止時間は誤発火と正常で差が無い

ユーザーの仮説（誤発火では掌上静止が短いのでは）を実データで検証した。

| | n | dwell_ms 範囲 | 中央値 |
|---|---|---|---|
| 誤発火 | 5 | 520–523 | 521 |
| 意図的 | 33 | 520–523 | 521 |

**完全に同一。**FW は 500 ms に達した瞬間に確定して `return` するため
（`main.c:2669-2674`）、`outbound_ready` v1 は常に ~521 になる。
**`pos_imp_at_lift` と同じ打ち切り変数**で、実際にどれだけ静止していたかを FW は見ていない。

軌跡の生サンプルで dwell 区間（21サンプル）の静止の質も測ったが分離しない
（誤発火 RMS 0.467 / 0.126、意図的 0.228 / 1.674 / 0.370 — 最も静かなのは誤発火）。

### dwell 延長（500→1000 ms）は逆効果。実装しない

「延長で誤発火が減るなら直す」という条件付きの依頼だったので、まず測った。
軌跡には dwell 完了後の生サンプルが残っているため、FW の dwell 継続条件
（`main.c:2623-2626`: RMS > `GESTURE_START_QUIET_ACCEL_MS2` 4.0、または
候補方向からの傾き > `GESTURE_PALM_UP_DWELL_TILT_MAX_DEG` 20°）を
後付けで適用すれば、任意の要求時間をシミュレートできる。

| 要求 dwell | 誤発火 11:50 | 誤発火 11:55 | 意図 13:58 | 意図 13:57 | 意図 07:58 |
|---|---|---|---|---|---|
| 500（現行） | PASS | PASS | PASS | PASS | PASS |
| 800 | PASS | PASS | PASS | **BREAK 757ms** | PASS |
| **1000** | **PASS** | **PASS** | PASS | **BREAK 757ms** | PASS |
| 1500 | PASS | PASS | PASS | BREAK 757ms | **BREAK 1126ms** |
| 2000 | **PASS** | **PASS** | BREAK 1751ms | BREAK 757ms | BREAK 1126ms |

**1000 ms でも誤発火は2件とも素通りし、意図的ジェスチャーが先に落ちる。**
800 ms 以上のどの値でも向きは同じで、2000 ms にしても誤発火だけが生き残る。

理由: 外来では腕が**実際に静止している**（聴診、カルテ保持、安静時）。
誤発火 11:55 の dwell RMS 0.126 は8件中最も静かだった。一方、意図的ジェスチャーは
**さっさと次の動作に移る**ので、13:57 は 757 ms で傾き 20.5° を超えて破棄される —
これはジェスチャーそのものを始めた瞬間である。
**dwell 延長はユーザーに「待て」と要求する変更**であり、意図的動作の方が先に壊れる。

標本は誤発火2件・意図的3件（＋未分類3件）と小さいが、
**8件全てで向きが一貫しており、機序も説明がつく。**
依頼の条件（誤発火を減らせるなら）が満たされないため、この変更は行わない。

### ゴール

(a) 日本語と英語以外を幻聴として抑制する、(b) ダブルタップで応答待ちと読み上げを中断する、
(c) 前回の作業で判明した解析窓の誤りを直す。**FW は変更しない。**

---

## Step 0 — この計画を documents/ に一時ファイルとして保存 ✅ 完了

`voice-harness-android/documents/WIP_gesture_gate_plan_2026-09-01.md`

`documents/` は全て小文字スネークケースなので、**大文字の `WIP_` 接頭辞**が
一覧で一目で浮く。日付も名前に入れる。

先頭に破棄条件を明記した見出しを置く:

```markdown
> **一時ファイル — 作業中の計画です**
> 作成 2026-09-01。Step A–D が実装され `gesture_false_trigger.md` と
> `flex_pronation_gesture.md` に反映された時点で**このファイルは削除する**。
> 恒久的な仕様・調査結果はそちらを参照すること。
```

本文はこの計画そのもの（調査結果の数表を含む） — **つまりこのファイル**。
実装が進んだら、確定した内容は恒久ドキュメントへ移し、このファイルは消す。

---

## Step A — 発話ゲートを日本語・英語のみに

`app/src/main/java/com/g150446/voiceharness/UtteranceIntentGate.kt`

判定順序は変えない（依頼形・疑問形・アプリコマンドの PASS が最優先）。
その後の言語判定を書き換える:

| 条件 | 判定 |
|---|---|
| かな（`぀-ヿ`）を含む | 日本語 → 既存の相槌・断片規則へ |
| 漢字を含みかなを含まない | **`SUPPRESS_FOREIGN`**（中国語） |
| ハングル・キリル・タイ・アラビア・デーヴァナーガリー・ギリシャ・ヘブライを含む | **`SUPPRESS_FOREIGN`** |
| ラテン文字のみ、かつ**依頼形でない** | **`SUPPRESS_NON_REQUEST`** |
| ラテン文字のみ、かつ依頼形 | PASS |

英語の依頼形判定は、行頭の動詞・疑問詞、または `?` の存在:

```
^(tell|set|show|give|find|search|play|remind|explain|write|read|open|close|
  stop|start|make|create|send|help|list|add|turn|call|check|translate|
  summari[sz]e|describe|what|who|when|where|why|how|which|can|could|would|
  will|should|is|are|do|does|did|please)\b     … または  \?
```

**実データ検証済み**: 履歴100件の非CJK書き起こし9種（`I'm going to go.`
`Thank you.` `All right.` `I'm sorry.` `PHONE RINGS` `BEEP BEEP BEEP` 等）は
**9/9 が抑制**され、依頼形の英語は1件も存在しなかった。現在の挙動を保ったまま
本物の英語依頼だけを通せる。漢字のみ日本語（「血圧」等）の誤遮断リスクは残るが、
履歴100件で該当は誤発火1件のみ。

テスト（`UtteranceIntentGateTest.kt`）:
- 実測の `這 因為怎麼吃掉` が `SUPPRESS_FOREIGN`
- `안녕하세요` が `SUPPRESS_FOREIGN`
- 実測9種の英語幻聴が `SUPPRESS_NON_REQUEST`
- `What is the weather today?` / `Set a reminder for 5pm` が PASS
- 既存の日本語コマンド（`ホーム画面からパススルーモード` 等）が PASS のまま

---

## Step B — ダブルタップで応答待ち・読み上げを中断

`app/src/main/java/com/g150446/voiceharness/VoiceProcessor.kt`

既存資産を使う: `cancelAssistantRequest()`（`:714`）が既に
`aiBackend.cancel()` + `stopSpeaking()` + `releaseAssistantProcessing()` を行う。

1. **パイプラインの Job を保持する** — ハーネス経路は `:352` で
   `scope.launch(Dispatchers.IO)` を戻り値を捨てて起動している。
   `private var harnessPipelineJob: Job? = null` に代入する。

2. **`handleDoubleTap()`（`:1156`）の先頭で状態を見る** —
   `BleConnectionService.voiceState.value` が
   `TRANSCRIBING` / `RESPONDING` / `SPEAKING` のいずれかなら中断して `return`。
   それ以外は既存の読み上げパススルー処理へ落ちる（現在の機能を壊さない）。

3. **中断処理** — `harnessPipelineJob?.cancel()`、`aiBackend.cancel()`、
   `stopSpeaking()`、`BleConnectionService.setVoiceState(VoiceState.READY)`、
   画面に「中断しました」を出す。履歴には中断として1件残す
   （何を中断したか後から追えないと調整できない。既存の `errorMessage` 方式に合わせる）。

4. **リマインダー・アラームの中断** — `:896-902` で
   `reminderRepository.addEntry()` → `ReminderAlarmScheduler.schedule()` を
   同期的に呼んでおり、コルーチンのキャンセルだけでは競合しうる。
   - 中断フラグを立て、`schedule()` の直前で確認して打ち切る
   - 既に登録済みだった場合は `ReminderAlarmScheduler.cancel()`
     （`VoiceViewModel.kt:205` で使用中）と `reminderRepository` からの削除で巻き戻す

`RECORDING` 中は対象外（録音停止はジェスチャー側の役割で、二重の停止手段を作らない）。

テスト: 中断判定は `VoiceState` を引数に取る純粋関数
（`shouldInterruptOnDoubleTap(state): Boolean`）に切り出し、素の JUnit で全状態を検証する。

---

## Step C — オフライン解析窓の是正

`harness-node/mac_client/gesture_feature_eval.py`

前回、私のオフライン再現が FW と 2.4 倍ずれていた（1.402 vs FW の `match` v2 = 3.3211）。

**積分窓の終端は hold 進入**。実測4件すべてで `final_hold_start` v1 == `match` v2
（0.8471 / 0.7827 / 0.9667 / 3.3211）であり、**静止 hold 中に `pos_impulse` は増えない**。
前回報告した「hold 中に 1.46 積む」は私の窓設定の誤りで、FW の挙動ではない。**撤回する。**

新コマンド `verify-window <csv...>`:

1. 各CSVの `# live` 行から `match` (0x09) v2 を正解値として読む
2. 生サンプルから状態機械を回し、hold 進入で積分を止める
   （掌下ラッチ `main.c:2735` と hold 進入条件 `main.c:2995` を再現する必要がある）
3. **数%以内で一致することを表示し、合わなければ非ゼロ終了する**

このチェックが通るまで候補特徴の数値は報告しない。
`synthetic-rotation` は正解が 0 と定義できるので窓に依存せず、そのまま残す。

Android CSV ローダは `mac_client/imu_trajectory.py` に
`load_android_trajectory_csv()` を追加し、`# session` / `# milestone` / `# live` を
`meta` / `milestones` / `live` に分けて既存 `load_trajectory_csv()` と同じ dict 形へ正規化する。

---

## Step D — ドキュメント

`harness-node/docs/flex_pronation_gesture.md`

- 「静止 hold 中に 1.46 積む」を**撤回**し、`final_hold_start` v1 == `match` v2 を記す
- **dwell が第3の打ち切り変数**であること（誤発火5件・意図的33件とも 520–523 ms）
- **dwell 延長シミュレーションの結果表**（500/800/1000/1500/2000 ms）と、
  延長が逆効果である理由。同じ検証を再実行できるよう
  `gesture_feature_eval.py` に `dwell-sweep <csv...>` として残す
- peak |ωy| の分離（誤発火 n=6 中央値 324 / 意図的 n=39 中央値 569、
  400 dps で維持90%・削減83%）と、**573.4 dps で飽和**していること
  （`GYRO_FULL_SCALE_DPS=500`、17.5 mdps/LSB × 32768）

`voice-harness-android/documents/gesture_false_trigger.md`

- 中国語漏れの事例と実害、言語判定の新規則
- ダブルタップ中断の仕様

ここまで反映できたら **Step 0 の `WIP_gesture_gate_plan_2026-09-01.md` を削除する。**

---

## Step E — 保留（データが揃ってから判断）

- **dwell 要求の延長** — 上記のとおり測定済みで逆効果。実装しない。
  dwell 系で残る可能性があるのは「静止の長さ」ではなく**姿勢の絶対的な向き**だが、
  FW は加速度だけでは掌の上下を判別できない（`flex_pronation_gesture.md` の既知の制約）
- **`GYRO_FULL_SCALE_DPS` を上げて peak ωy の飽和を解く** — 分解能が半減し
  `accumulate_gyro_roll()` の精度に影響する
- **peak ωy をゲートに採用** — Go基準（維持95%/削減70%）に届かず、
  標本も 6 対 39、意図的ラベルは書き起こしからの発見的分類

11:50 / 11:55 と 08-31 10:00–12:00 の4件は履歴の `gestureLabel` が未設定。
Phase 2 で入れた時間帯一括ラベル UI（`MainActivity.kt`）で「誤発火」に落としてもらう。

---

## Verification

```bash
./gradlew :app:testDebugUnitTest --tests '*UtteranceIntentGateTest*' --tests '*VoiceProcessor*'
./gradlew :app:testDebugUnitTest        # 全体（現在 33スイート183件）

python3 harness-node/mac_client/gesture_feature_eval.py synthetic-rotation
python3 harness-node/mac_client/gesture_feature_eval.py verify-window \
  <09-01 取得の4件のCSV>
# dwell 延長が逆効果であることの再現（上の表と一致すること）
python3 harness-node/mac_client/gesture_feature_eval.py dwell-sweep \
  --limits 500 800 1000 1500 2000 <ラベル付きCSV>
```

実機（Step A/B はアプリのビルドが要る）:

1. ジェスチャー後に無言 → 中国語や英語の幻聴が出ても LLM も TTS も動かないこと
2. `What is the weather today?` と話して応答が返ること
3. **応答待ち中にダブルタップ → 中断すること**
4. **読み上げ中にダブルタップ → 止まること**
5. 「明日9時にリマインダー」と言い、確定前にダブルタップ →
   アラームが登録されていないこと（`VoiceViewModel` のリマインダー一覧で確認）
6. 読み上げパススルー中のダブルタップが従来どおりページ送りになること（非退行）

**APK は 151 MB、Tailscale 経由は 0.24 MB/s で転送が落ちる。**
導入は自宅 Wi-Fi か USB で行う。`-Parm64Only=true` で x86_64（84 MB）を除ける。
