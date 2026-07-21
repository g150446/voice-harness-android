# On-device Gemma 4 (E2B) 統合メモ

## 概要

STT / LLM を Groq API から **端末内 Gemma 4 E2B (LiteRT-LM)** に置換。
TTS は従来どおり Android `TextToSpeech`。

## 依存

- `com.google.ai.edge.litertlm:litertlm-android:0.14.0`
- Kotlin **2.3.0**（LiteRT-LM 0.14 の metadata 2.3 対応）

## モデル配置

推奨ファイル名: `gemma-4-E2B-it.litertlm`
HF: `litert-community/gemma-4-E2B-it-litert-lm`（約 2.5GB）

```bash
# 例: 端末のアプリ filesDir へ push
adb shell mkdir -p /data/data/com.g150446.voiceharness/files/models
adb push gemma-4-E2B-it.litertlm /data/data/com.g150446.voiceharness/files/models/
```

またはモデル設定画面のファイル選択から取り込む。

探索順:
1. SharedPreferences の明示パス
2. `context.filesDir/models/*.litertlm`
3. `context.getExternalFilesDir(null)/models/*.litertlm`
4. 共有Downloadディレクトリ

## パイプライン

```
BLE PCM → VAD → Gemma ASR (audio) → Gemma Chat (text + set_reminder tool) → TTS
```

## 計測ログ (logcat)

```bash
adb logcat -s VoiceProcessor:D GemmaOnDeviceBackend:D ModelManager:D
```

- `Model loaded in N ms`
- `ASR done in N ms`
- `Chat done in N ms`

UI の Model Settings / ホームの Model 行にも Load / ASR / Chat ms を表示。

## Phase 0 Go/No-Go (razr 50s)

1. Settings → Load model now
2. 録音ジェスチャーで短文日本語
3. 連続 5 回で熱・遅延・クラッシュを確認

目安:
- Load 成功、OOM なし
- ASR 15秒音声でおおよそ 10 秒以内
- 短文 Chat おおよそ 10 秒以内

## 未対応 / 今後

- モデル自動ダウンロード UI
- GPU バックエンド自動選択
- E4B 切替
- ネットワークAPIフォールバック（方針上なし）
