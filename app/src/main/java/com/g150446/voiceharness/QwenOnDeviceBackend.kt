package com.g150446.voiceharness

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Qwen3-ASR GGUF handles speech recognition; Qwen 3.5 LiteRT-LM handles chat.
 */
class QwenOnDeviceBackend(
    private val appContext: Context
) : VoiceAiBackend {

    override val name: String = "Qwen3.5-0.8B"
    override val profile: OnDeviceProfile = OnDeviceProfile.QWEN

    private val mutex = Mutex()
    private var engine: Engine? = null
    private var loadedModelPath: String? = null
    private val asrCli = QwenAsrCli(appContext)
    private val pendingReminder = AtomicReference<ReminderToolArgs?>(null)

    override suspend fun ensureReady(): Result<Unit> = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val modelFile = ModelManager.resolveQwenLlmModel(appContext)
                    ?: error("Qwen LLM モデルが見つかりません（.litertlm）。")
                val decoder = ModelManager.resolveQwenAsrDecoder(appContext)
                    ?: error("Qwen3-ASR decoder が見つかりません（${ModelManager.QWEN_ASR_DECODER_FILE}）。")
                val projector = ModelManager.resolveQwenAsrProjector(appContext)
                    ?: error("Qwen3-ASR projector が見つかりません（${ModelManager.QWEN_ASR_PROJECTOR_FILE}）。")
                require(asrCli.executable.isFile && asrCli.executable.canExecute()) {
                    "Qwen3-ASR native runtime is not available for this device"
                }
                ensureEngineLocked(modelFile)
                ModelManager.markSlotReady(ModelSlot.QWEN_ASR_DECODER, decoder.absolutePath, 0)
                ModelManager.markSlotReady(ModelSlot.QWEN_ASR_PROJECTOR, projector.absolutePath, 0)
            }.onFailure { e ->
                Log.e(TAG, "ensureReady failed", e)
                ModelManager.markSlotError(ModelSlot.QWEN_LLM, e.message ?: "Qwen 準備失敗")
                releaseLocked()
            }
        }
    }

    private fun ensureEngineLocked(modelFile: File) {
        val path = modelFile.absolutePath
        if (engine != null && loadedModelPath == path) {
            ModelManager.markSlotReady(ModelSlot.QWEN_LLM, path, ModelManager.status.value.lastLoadMs)
            return
        }
        releaseLocked()
        ModelManager.markSlotLoading(ModelSlot.QWEN_LLM, path)
        val started = System.currentTimeMillis()
        Log.d(TAG, "Loading Qwen LLM: $path (${ModelManager.formatSize(modelFile.length())})")
        engine = LitertLlmSupport.createEngine(
            context = appContext,
            modelPath = path,
            enableAudio = false,
            preferGpu = false
        )
        loadedModelPath = path
        val loadMs = System.currentTimeMillis() - started
        ModelManager.markSlotReady(ModelSlot.QWEN_LLM, path, loadMs)
        Log.d(TAG, "Qwen LLM loaded in $loadMs ms")
    }

    override suspend fun transcribe(audioFile: File): Result<TranscriptionResult> = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                check(engine != null) { "Qwen profile is not ready" }
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
                val currentEngine = engine ?: error("Qwen LLM is not ready")
                LitertLlmSupport.runChat(
                    engine = currentEngine,
                    conversationHistory = conversationHistory,
                    languageCode = languageCode,
                    pendingReminder = pendingReminder,
                    temperature = 0.7,
                    tag = TAG
                )
            }.onFailure { e -> Log.e(TAG, "chat failed", e) }
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
        try {
            engine?.close()
        } catch (e: Exception) {
            Log.w(TAG, "engine.close failed: ${e.message}")
        }
        engine = null
        loadedModelPath = null
    }

    private companion object {
        private const val TAG = "QwenOnDeviceBackend"
    }
}
