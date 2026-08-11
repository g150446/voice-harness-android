package com.g150446.voiceharness

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Engine
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns a text-only LiteRT-LM engine used independently from the active ASR model.
 * Callers serialize access with their backend mutex.
 */
internal class DedicatedChatEngine(
    private val appContext: Context,
    private val tag: String
) {
    private var engine: Engine? = null
    private var loadedModelPath: String? = null
    private val pendingReminder = AtomicReference<ReminderToolArgs?>(null)

    val isReady: Boolean
        get() = engine != null

    fun isLoaded(modelFile: File): Boolean =
        engine != null && loadedModelPath == modelFile.absolutePath

    fun ensureReady(modelFile: File, slot: ModelSlot): Result<Unit> = runCatching {
        val path = modelFile.absolutePath
        if (engine != null && loadedModelPath == path) {
            ModelManager.markSlotReady(slot, path, ModelManager.status.value.lastLoadMs)
            return@runCatching
        }

        release()
        ModelManager.markSlotLoading(slot, path)
        val started = System.currentTimeMillis()
        Log.d(tag, "Loading Chat model: $path (${ModelManager.formatSize(modelFile.length())})")
        val loaded = LitertLlmSupport.createEngine(
            context = appContext,
            modelPath = path,
            enableAudio = false,
            preferGpu = true,
            maxNumTokens = FAST_CHAT_CONTEXT_TOKENS
        )
        engine = loaded
        loadedModelPath = path
        val loadMs = System.currentTimeMillis() - started
        ModelManager.markSlotReady(slot, path, loadMs)
        Log.d(tag, "Chat model loaded in $loadMs ms backend=${loaded.engineConfig.backend.name}")
    }.onFailure { error ->
        Log.e(tag, "Chat model initialization failed", error)
        release()
    }

    fun chat(
        conversationHistory: List<ConversationTurn>,
        languageCode: String?
    ): ChatResult {
        val currentEngine = engine ?: error("Chat engine is not ready")
        return LitertLlmSupport.runChat(
            engine = currentEngine,
            conversationHistory = conversationHistory,
            languageCode = languageCode,
            pendingReminder = pendingReminder,
            temperature = 0.2,
            tag = tag
        )
    }

    fun release() {
        try {
            engine?.close()
        } catch (e: Exception) {
            Log.w(tag, "Chat engine close failed: ${e.message}")
        }
        engine = null
        loadedModelPath = null
    }

    private companion object {
        private const val FAST_CHAT_CONTEXT_TOKENS = 1280
    }
}
