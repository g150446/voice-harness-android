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
 * Qwen3-ASR GGUF handles speech recognition; the compact shared LiteRT-LM model handles chat.
 */
class QwenOnDeviceBackend(
    private val appContext: Context
) : VoiceAiBackend {

    override val name: String = "Qwen3-ASR + FastChat"
    override val profile: OnDeviceProfile = OnDeviceProfile.QWEN

    private val mutex = Mutex()
    private val asrCli = QwenAsrCli(appContext)
    private val chatEngine = DedicatedChatEngine(appContext, TAG)

    override suspend fun ensureReady(): Result<Unit> = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val fastModel = ModelManager.resolveFastChatModel(appContext)
                val legacyModel = ModelManager.resolveQwenLlmModel(appContext)
                val modelFile = listOfNotNull(fastModel, legacyModel)
                    .firstOrNull(chatEngine::isLoaded)
                    ?: fastModel
                    ?: legacyModel
                    ?: error("Chatモデルが見つかりません（.litertlm）。")
                val chatSlot = if (modelFile == fastModel) ModelSlot.FAST_CHAT else ModelSlot.QWEN_LLM
                val decoder = ModelManager.resolveQwenAsrDecoder(appContext)
                    ?: error("Qwen3-ASR decoder が見つかりません（${ModelManager.QWEN_ASR_DECODER_FILE}）。")
                val projector = ModelManager.resolveQwenAsrProjector(appContext)
                    ?: error("Qwen3-ASR projector が見つかりません（${ModelManager.QWEN_ASR_PROJECTOR_FILE}）。")
                require(asrCli.executable.isFile && asrCli.executable.canExecute()) {
                    "Qwen3-ASR native runtime is not available for this device"
                }
                val fastResult = chatEngine.ensureReady(modelFile, chatSlot)
                if (fastResult.isFailure && fastModel != null && legacyModel != null) {
                    ModelManager.markSlotError(
                        ModelSlot.FAST_CHAT,
                        "高速Chatの読み込みに失敗。従来Qwen Chatを使用: ${fastResult.exceptionOrNull()?.message}"
                    )
                    chatEngine.ensureReady(legacyModel, ModelSlot.QWEN_LLM).getOrThrow()
                } else {
                    fastResult.getOrThrow()
                }
                ModelManager.markSlotReady(ModelSlot.QWEN_ASR_DECODER, decoder.absolutePath, 0)
                ModelManager.markSlotReady(ModelSlot.QWEN_ASR_PROJECTOR, projector.absolutePath, 0)
            }.onFailure { e ->
                Log.e(TAG, "ensureReady failed", e)
                val slot = if (ModelManager.resolveFastChatModel(appContext) != null) {
                    ModelSlot.FAST_CHAT
                } else {
                    ModelSlot.QWEN_LLM
                }
                ModelManager.markSlotError(slot, e.message ?: "Qwen 準備失敗")
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
                val started = System.currentTimeMillis()
                val result = asrCli.transcribe(audioFile, decoder, projector)
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
