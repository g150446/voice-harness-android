package com.g150446.voiceharness

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Gemma 4 E2B path (quality). Multimodal ASR + chat via one .litertlm engine.
 */
class GemmaOnDeviceBackend(
    private val appContext: Context
) : VoiceAiBackend {

    override val name: String = "Gemma4-E2B"
    override val profile: OnDeviceProfile = OnDeviceProfile.GEMMA

    private val mutex = Mutex()
    private var engine: Engine? = null
    private var loadedModelPath: String? = null
    private val pendingReminder = AtomicReference<ReminderToolArgs?>(null)
    private val fastChatEngine = DedicatedChatEngine(appContext, "$TAG/FastChat")

    override suspend fun ensureReady(): Result<Unit> = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val modelFile = ModelManager.resolveGemmaModel(appContext)
                    ?: error("Gemma モデルが見つかりません。files/models に .litertlm を配置してください。")
                val path = modelFile.absolutePath
                if (engine != null && loadedModelPath == path) {
                    ModelManager.markSlotReady(ModelSlot.GEMMA, path, ModelManager.status.value.lastLoadMs)
                    ensureFastChatBestEffort()
                    return@runCatching
                }
                releaseLocked()
                ModelManager.markSlotLoading(ModelSlot.GEMMA, path)
                val started = System.currentTimeMillis()
                Log.d(TAG, "Loading Gemma: $path (${ModelManager.formatSize(modelFile.length())})")
                engine = LitertLlmSupport.createEngine(
                    context = appContext,
                    modelPath = path,
                    enableAudio = true,
                    preferGpu = true
                )
                loadedModelPath = path
                val loadMs = System.currentTimeMillis() - started
                ModelManager.markSlotReady(ModelSlot.GEMMA, path, loadMs)
                Log.d(TAG, "Gemma loaded in ${loadMs} ms")

                ensureFastChatBestEffort()
                Unit
            }.onFailure { e ->
                Log.e(TAG, "ensureReady failed", e)
                ModelManager.markSlotError(ModelSlot.GEMMA, e.message ?: "Gemma 読み込み失敗")
                releaseLocked()
            }
        }
    }

    override suspend fun transcribe(audioFile: File): Result<TranscriptionResult> = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val eng = engine ?: error("Gemma engine not ready")
                if (!audioFile.isFile) error("Audio file missing: ${audioFile.absolutePath}")
                val started = System.currentTimeMillis()
                val baseLanguage = ModelManager.currentSpeechBaseLanguage(appContext)
                val asrPrompt = AsrPromptBuilder.build(baseLanguage)
                Log.d(TAG, "ASR baseLanguage=$baseLanguage")
                eng.createConversation(
                    ConversationConfig(
                        samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 0.0)
                    )
                ).use { conversation ->
                    val response = LitertLlmSupport.runGeneration(
                        conversation,
                        TAG,
                        LitertLlmSupport.ASR_TIMEOUT_MS
                    ) {
                        conversation.sendMessage(
                            Contents.of(
                                Content.Text(asrPrompt),
                                Content.AudioFile(audioFile.absolutePath)
                            )
                        )
                    }
                    val text = response.toString().trim()
                    val latency = System.currentTimeMillis() - started
                    ModelManager.recordAsrMs(latency)
                    Log.d(TAG, "ASR done in ${latency} ms: '${text.take(120)}'")
                    TranscriptionResult(
                        text = text,
                        languageCode = baseLanguage.languageCodeHint,
                        latencyMs = latency
                    )
                }
            }.onFailure { e ->
                Log.e(TAG, "transcribe failed", e)
                if (e is GenerationTimedOutException) releaseLocked()
            }
        }
    }

    override suspend fun chat(
        conversationHistory: List<ConversationTurn>,
        languageCode: String?
    ): Result<ChatResult> = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                if (fastChatEngine.isReady) {
                    fastChatEngine.chat(conversationHistory, languageCode)
                } else {
                    val eng = engine ?: error("Gemma engine not ready")
                    LitertLlmSupport.runChat(
                        engine = eng,
                        conversationHistory = conversationHistory,
                        languageCode = languageCode,
                        pendingReminder = pendingReminder,
                        temperature = 0.7,
                        tag = TAG
                    )
                }
            }.onFailure { e ->
                Log.e(TAG, "chat failed", e)
                if (e is GenerationTimedOutException) {
                    if (fastChatEngine.isReady) fastChatEngine.release() else releaseLocked()
                }
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

    private fun ensureFastChatBestEffort() {
        ModelManager.resolveFastChatModel(appContext)?.let { fastModel ->
            fastChatEngine.ensureReady(fastModel, ModelSlot.FAST_CHAT)
                .onFailure { error ->
                    ModelManager.markSlotError(
                        ModelSlot.FAST_CHAT,
                        "高速Chatの読み込みに失敗。Gemma Chatを使用: ${error.message}"
                    )
                }
        }
    }

    private fun releaseLocked() {
        fastChatEngine.release()
        try {
            engine?.close()
        } catch (e: Exception) {
            Log.w(TAG, "engine.close failed: ${e.message}")
        }
        engine = null
        loadedModelPath = null
    }

    private companion object {
        private const val TAG = "GemmaOnDeviceBackend"
    }
}
