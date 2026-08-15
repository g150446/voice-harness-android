# BLE 音声の信頼性と Bluetooth ヘッドセット併用

## 目的

HarnessNode の BLE マイクと Bluetooth ヘッドセットを同時に使う場合の音声経路、
BLE PCM の送達条件、障害時の切り分け方法をまとめる。

想定する構成は次のとおり。

```text
HarnessNode ── BLE Notify ──▶ Android ── A2DP ──▶ Bluetoothヘッドセット
      マイク入力                  ASR / Chat / TTS          AI応答の再生
```

Bluetooth ヘッドセットは AI 応答の再生先として利用できる。一方、録音開始・停止の
キュー音まで A2DP に送ると、BLE PCM の高頻度通知と同じ Bluetooth コントローラ上で
競合しやすいため、キュー音だけは Android 端末の内蔵スピーカーへ出す。

## 2026年8月の障害で確認した症状

- 音声入力と無関係な文字列が表示される
- `音声データを十分に受信できませんでした` と表示される
- Gemma プロファイルで `ASR error` が発生する
- Bluetooth ヘッドセット接続時に発生しやすい

実機ログでは、約 5,050 ms の録音に対して 580 ms、約 3,570 ms の録音に対して
1,340 ms しか PCM を受信できていない例があった。不完全な PCM を ASR に渡すと、
モデルは欠落部分を復元できず、無関係な文字列を生成することがある。

また、録音開始音が `STREAM_MUSIC` で再生され、Soundcore Q20i の A2DP ストリームが
録音期間をまたいで動作していた。これは BLE 通知欠落と同時に観測された重要な兆候で
あり、ヘッドセット併用時は録音キュー音の出力先を分離する必要がある。

## 複合していた原因

### 1. 録音キュー音が A2DP を起動していた

以前は `ToneGenerator(AudioManager.STREAM_MUSIC)` を使用していたため、Bluetooth
ヘッドセット接続中は短いキュー音でも A2DP が開始された。HarnessNode は 20 ms の
音声フレームを最大4通知へ分割し、約200 notifications/sで送る。A2DPとの同時利用は
端末のBluetoothコントローラ、スケジューリング、無線時間の競合要因になる。

現行実装は `RecordingCuePlayer` が `AudioTrack` と
`AudioDeviceInfo.TYPE_BUILTIN_SPEAKER` を使う。内蔵スピーカーを選択できない場合は、
Bluetoothへフォールバックせずキュー音を省略する。

TTSによるAI応答は通常の音声経路を使うため、録音終了後にBluetoothヘッドセットから
再生してよい。分離対象は録音開始・停止のキュー音である。

### 2. ファームウェアが Notify の失敗を送達済みとして扱っていた

以前のファームウェアは `bt_gatt_notify()` の戻り値を十分に扱わず、通知が受理されなく
ても PCM オフセットとシーケンス番号を進める場合があった。これにより、Android側から
見ると復元不能なPCM欠落になった。

現行ファームウェアは次の規約で送信する。

- `bt_gatt_notify_cb()` の完了コールバックで送信枠を返す
- 同時送信は6通知までに制限する
- `-ENOMEM` / `-EAGAIN` は短時間待って再試行する
- 通知が受理された後だけPCMオフセットとシーケンス番号を進める
- 録音停止イベント `0x02` の前に、受理済みPCM通知の完了を待つ

### 3. Android内でPCMと録音イベントを別経路に流していた

以前はPCMと録音イベントを別々の `MutableSharedFlow` へ `tryEmit()` していた。
バッファ満杯時にPCMを失う可能性があり、別経路の録音停止イベントが残りのPCMより先に
処理される可能性もあった。

現行実装は `BleVoiceInput.Audio` と `BleVoiceInput.Event` を単一の
`Channel.UNLIMITED` に投入する。`BleConnectionService` の1つのcollectorが
`VoiceProcessor.handleBleInput()`へ渡すため、GATTコールバックで観測した順序を保つ。

`VoiceProcessor` は `BleManager` の開始前に生成する。接続直後の録音開始イベントを
consumer未生成の状態で失わないためである。

### 4. BLE接続パラメータが音声ストリーム向けではなかった

AndroidはGATT接続時と録音開始時に
`BluetoothGatt.CONNECTION_PRIORITY_HIGH` を要求する。これは端末側への要求であり、
採用される接続間隔を保証するAPIではないが、高レート通知を受ける意図を明示できる。

### 5. Gemmaと高速Chatの同時エンジンが端末リソースを競合した

GemmaプロファイルでGemmaのASRエンジンと別の高速Chatエンジンを同時に保持すると、
razr 50s上でGPU delegateやメモリ資源が競合し、ASR初期化エラーになる場合があった。

現行の `GemmaOnDeviceBackend` は1つのGemmaエンジンをASRとChatで共有し、mutexで
直列実行する。Chatの速度よりASRの安定性とメモリ使用量を優先した設計である。

## 不完全PCMをASRへ渡さない防御

16 kHz、16-bit、モノラルPCMの期待データ量は毎秒32,000 bytesである。

```text
pcmDurationMs = pcmBytes * 1000 / 32000
completeness  = pcmDurationMs / recordingWallTimeMs
```

録音時間が1,000 ms以上の場合、現行アプリは `completeness >= 0.70` を必須とする。
下回った場合はASRを実行せず、次のメッセージを表示する。

```text
音声データを十分に受信できませんでした（もう一度話してください）
```

これは障害の原因ではなく、不完全な音声によるASRの幻覚を画面へ出さないための安全策で
ある。このメッセージが出た場合、VADやモデルの精度より先にBLE送達を調べる。

1秒未満の短い操作は、この完全性判定では拒否せず、既存の最小長・VAD判定に任せる。

## 調査手順

### 1. アプリの受信量を確認する

```bash
adb logcat -c
adb logcat -v threadtime -s BleManager:D VoiceProcessor:D \
  RecordingCuePlayer:D BleConnectionService:D GemmaOnDeviceBackend:D AndroidRuntime:E
```

録音停止時のログ例：

```text
BLE recording stopped by firmware: wall=4200ms, pcm=4050ms (129600 bytes)
```

確認項目：

- `pcm / wall` が70%以上、通常は可能な限り100%に近いこと
- `PCM gap` が連続していないこと
- `Incomplete BLE PCM capture` がないこと
- `Requested high BLE connection priority (connected)` があること
- 録音開始時にも同じ高優先度要求があること

### 2. Bluetoothヘッドセットの再生状態を確認する

問題がヘッドセット接続時だけ起きる場合は、録音区間中にA2DPが開始・停止していないか
Bluetoothログを確認する。特に録音開始音の直後から数秒間A2DPが継続する場合は、
キュー音が内蔵スピーカーへルーティングされているかを確認する。

電話や通話アプリによるHFP/SCOへの切り替えは別経路のため、録音試験中は避ける。
HarnessNodeが入力元なので、ヘッドセットマイクを有効にする必要はない。

### 3. 原因を層ごとに分ける

| ログ・症状 | 最初に調べる場所 |
|---|---|
| `PCM gap`、完全性70%未満 | BLE接続品質、ファームウェアNotify、A2DP同時動作 |
| PCMは十分だがVADで無音 | マイク信号、Silero VAD、FFT fallback |
| PCMとVADは正常だが文字が違う | ASRモデル、言語指定、音量・発話内容 |
| Gemmaの初期化・ASR error | 複数エンジン、GPU delegate、メモリ不足 |
| 文字起こしは正常だが音声応答なし | Android TTS、A2DP出力、音量、TTS locale |

## Bluetoothヘッドセット併用の受け入れ試験

1. Bluetoothヘッドセットを接続し、メディア出力先になっていることを確認する
2. HarnessNodeのバッテリーとBLE接続を確認する
3. 3〜5秒の日本語短文を5回録音する
4. 各試行で `wall` と `pcm`、シーケンス欠落、転写結果を記録する
5. 録音キュー音が端末スピーカー、AI応答がヘッドセットから再生されることを確認する
6. 5回すべてで完全性70%以上、受信不足メッセージなし、意味の合う転写なら合格とする

試験開始前にログ収集担当と発話担当で、試行回数、発話文、開始合図を合意する。物理操作を
伴う遠隔試験では、準備完了の明示確認を得てからログ収集を開始する。

## 運用上の注意

- HarnessNodeの低バッテリー時は、再現試験の前に充電する
- 録音中に音楽再生や通話開始など、別のBluetooth音声経路を意図的に開始しない
- キュー音が聞こえない場合でも、内蔵スピーカーへ安全にルーティングできなければ省略する
  仕様なので、Bluetoothへ戻す変更は行わない
- 完全性の70%閾値を下げてエラー表示だけを隠さない。先にPCM欠落を修正する
- `Channel.UNLIMITED` は短時間の録音を前提にした損失回避策である。録音時間を無制限に
  する場合は、順序を維持した永続キューや明示的な上限を別途設計する

## 関連実装

- `RecordingCuePlayer.kt`: 内蔵スピーカー限定の録音キュー音
- `BleManager.kt`: 高優先度要求、PCMとイベントの単一順序付きChannel
- `BleConnectionService.kt`: VoiceProcessorの先行生成と単一collector
- `VoiceProcessor.kt`: PCM時間の計測と完全性判定
- `BleSpeechPolicy.kt`: 完全性閾値
- `GemmaOnDeviceBackend.kt`: Gemma ASR / Chatの単一エンジン直列実行
- `harness-node/nordic-main/src/main.c`: Notifyのbackpressureと録音停止前drain

対応コミット：

- Android (`voice-harness-android`): `5e0695c`
- Firmware (`harness-node`): `44d3590`

両コミットをrazr 50sとHarnessNodeへ導入後、実際の音声入力で正常動作を確認した。
