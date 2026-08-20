package com.g150446.voiceharness

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Qwen3-ASR GGUF handles speech recognition; LFM 2.5 2.6B (LEAP) handles chat.
 */
class QwenOnDeviceBackend(
    private val appContext: Context
) : VoiceAiBackend {

    override val name: String = "Qwen3-ASR + LFM2.5"
    override val profile: OnDeviceProfile = OnDeviceProfile.QWEN

    private val mutex = Mutex()
    private val asrCli = QwenAsrCli(appContext)
    private val chatEngine = LeapChatEngine(appContext)

    override suspend fun ensureReady(): Result<Unit> = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val chatModel = ModelManager.resolveLfmChatModel(appContext)
                    ?: error("LFM Chat が見つかりません（${ModelManager.LFM_CHAT_FILE}）。")
                val decoder = ModelManager.resolveQwenAsrDecoder(appContext)
                    ?: error("Qwen3-ASR decoder が見つかりません（${ModelManager.QWEN_ASR_DECODER_FILE}）。")
                val projector = ModelManager.resolveQwenAsrProjector(appContext)
                    ?: error("Qwen3-ASR projector が見つかりません（${ModelManager.QWEN_ASR_PROJECTOR_FILE}）。")
                require(asrCli.executable.isFile && asrCli.executable.canExecute()) {
                    "Qwen3-ASR native runtime is not available for this device"
                }
                chatEngine.ensureReady(chatModel, ModelSlot.LFM_CHAT).getOrThrow()
                ModelManager.markSlotReady(ModelSlot.QWEN_ASR_DECODER, decoder.absolutePath, 0)
                ModelManager.markSlotReady(ModelSlot.QWEN_ASR_PROJECTOR, projector.absolutePath, 0)
            }.onFailure { e ->
                Log.e(TAG, "ensureReady failed", e)
                ModelManager.markSlotError(ModelSlot.LFM_CHAT, e.message ?: "Qwen + LFM 準備失敗")
                releaseLocked()
            }
        }
    }

    override suspend fun transcribe(audioFile: File): Result<TranscriptionResult> = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                check(chatEngine.isReady) { "Qwen profile is not ready" }
                require(audioFile.isFile) { "Audio file missing: ${audioFile.absolutePath}" }
                val decoder = ModelManager.resolveQwenAsrDecoder(appContext)
                    ?: error("Qwen3-ASR decoder is missing")
                val projector = ModelManager.resolveQwenAsrProjector(appContext)
                    ?: error("Qwen3-ASR projector is missing")
                val baseLanguage = ModelManager.currentSpeechBaseLanguage(appContext)
                val vocabulary = AsrVocabularyCatalog.all(appContext)
                val asrPrompt = AsrPromptBuilder.build(baseLanguage, vocabulary)
                val started = System.currentTimeMillis()
                Log.d(TAG, "ASR baseLanguage=$baseLanguage vocabulary=${vocabulary.size}")
                val result = asrCli.transcribe(audioFile, decoder, projector, asrPrompt)
                val latency = System.currentTimeMillis() - started
                ModelManager.recordAsrMs(latency)
                Log.d(TAG, "ASR done in $latency ms: '${result.text.take(120)}'")
                TranscriptionResult(text = result.text, languageCode = result.languageCode, latencyMs = latency)
            }.onFailure { e -> Log.e(TAG, "transcribe failed", e) }
        }
    }

    override suspend fun chat(
        conversationHistory: List<ConversationTurn>,
        languageCode: String?
    ): Result<ChatResult> = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                chatEngine.chat(conversationHistory, languageCode)
            }.onFailure { e ->
                Log.e(TAG, "chat failed", e)
                if (e is GenerationTimedOutException) releaseLocked()
            }
        }
    }

    override fun release() {
        try {
            runBlocking { mutex.withLock { releaseLocked() } }
        } catch (e: Exception) {
            Log.w(TAG, "release error: ${e.message}")
            releaseLocked()
        }
        ModelManager.refresh(appContext)
    }

    private fun releaseLocked() {
        chatEngine.release()
    }

    private companion object {
        private const val TAG = "QwenOnDeviceBackend"
    }
}
