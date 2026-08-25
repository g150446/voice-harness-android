package com.g150446.voiceharness

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Routes STT and LLM independently while sharing loaded local backends.
 */
class OnDeviceAiFacade(
    private val appContext: Context
) : VoiceAiBackend {

    private val registry = BackendRegistry(appContext)

    override val profile: OnDeviceProfile
        get() = ModelManager.currentProfile(appContext)

    override val name: String
        get() {
            val stt = ModelManager.currentSttBackend(appContext)
            val llm = ModelManager.currentLlmBackend(appContext)
            return "Facade(stt=${stt.name},llm=${llm.name})"
        }

    fun switchProfile(profile: OnDeviceProfile) {
        ModelManager.setProfile(appContext, profile)
        registry.releaseUnused(
            ModelManager.currentSttBackend(appContext),
            ModelManager.currentLlmBackend(appContext),
        )
        ModelManager.refresh(appContext)
        Log.d(TAG, "Profile set to $profile")
    }

    fun switchSttBackend(backend: SttBackendId) {
        ModelManager.setSttBackend(appContext, backend)
        registry.releaseUnused(
            ModelManager.currentSttBackend(appContext),
            ModelManager.currentLlmBackend(appContext),
        )
        ModelManager.refresh(appContext)
        Log.d(TAG, "STT backend set to $backend")
    }

    fun switchLlmBackend(backend: LlmBackendId) {
        ModelManager.setLlmBackend(appContext, backend)
        registry.releaseUnused(
            ModelManager.currentSttBackend(appContext),
            ModelManager.currentLlmBackend(appContext),
        )
        ModelManager.refresh(appContext)
        Log.d(TAG, "LLM backend set to $backend")
    }

    private fun sttBackend(): VoiceAiBackend = when (ModelManager.currentSttBackend(appContext)) {
        SttBackendId.GEMMA -> registry.obtainGemma()
        SttBackendId.QWEN -> registry.obtainQwen()
        SttBackendId.GROQ -> registry.obtainGroq()
    }

    override suspend fun ensureReady(): Result<Unit> {
        val sttReady = sttBackend().ensureReady()
        if (sttReady.isFailure) return sttReady
        return when (ModelManager.currentLlmBackend(appContext)) {
            LlmBackendId.GEMMA -> registry.obtainGemma().ensureReady()
            LlmBackendId.QWEN -> registry.obtainQwen().ensureReady()
            LlmBackendId.GROQ -> registry.obtainGroq().ensureReady()
            LlmBackendId.OPENROUTER -> registry.obtainOpenRouter().ensureReady()
        }
    }

    override suspend fun transcribe(
        audioFile: File,
        vocabulary: List<AsrVocabularyTerm>
    ): Result<TranscriptionResult> {
        val backend = sttBackend()
        if (ModelManager.currentSttBackend(appContext).isCloud) {
            return backend.transcribe(audioFile, vocabulary)
        }
        if (vocabulary.isNotEmpty()) {
            return backend.transcribe(audioFile, vocabulary)
        }
        val all = AsrVocabularyCatalog.all(appContext)
        val firstPassVocab = AsrVocabularyCatalog.firstPassTerms(all)
        val first = backend.transcribe(audioFile, firstPassVocab)
        val firstResult = first.getOrNull() ?: return first
        if (!AsrVocabularyCatalog.shouldRetryWithTriggeredTerms(firstResult.text, all)) {
            return first
        }
        val retryVocab = AsrVocabularyCatalog.retryTerms(firstResult.text, all)
        Log.d(
            TAG,
            "ASR retry with triggered vocabulary=${retryVocab.joinToString { it.writtenForm }}"
        )
        val second = backend.transcribe(audioFile, retryVocab)
        val secondResult = second.getOrNull()
        if (secondResult != null &&
            !AsrTextFilter.isVocabularyEchoWithoutTrigger(secondResult.text, retryVocab)
        ) {
            return Result.success(
                secondResult.copy(latencyMs = firstResult.latencyMs + secondResult.latencyMs)
            )
        }
        Log.w(TAG, "ASR retry discarded — keeping first pass '${firstResult.text.take(80)}'")
        return first
    }

    override suspend fun chat(request: ChatRequest): Result<ChatResult> =
        when (ModelManager.currentLlmBackend(appContext)) {
            LlmBackendId.GEMMA -> registry.obtainGemma().chat(request)
            LlmBackendId.QWEN -> registry.obtainQwen().chat(request)
            LlmBackendId.GROQ -> registry.obtainGroq().chat(request)
            LlmBackendId.OPENROUTER -> registry.obtainOpenRouter().chat(request)
        }

    override fun cancel() {
        registry.cancelOpenRouter()
    }

    override fun release() {
        registry.releaseAll()
    }

    private companion object {
        private const val TAG = "OnDeviceAiFacade"
    }
}
