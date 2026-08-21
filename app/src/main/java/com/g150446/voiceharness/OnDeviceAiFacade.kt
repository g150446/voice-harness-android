package com.g150446.voiceharness

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Profile-aware facade. Default is GEMMA; Qwen remains selectable.
 */
class OnDeviceAiFacade(
    private val appContext: Context
) : VoiceAiBackend {

    private val active = AtomicReference<VoiceAiBackend?>(null)

    override val profile: OnDeviceProfile
        get() = ModelManager.currentProfile(appContext)

    override val name: String
        get() = active.get()?.name ?: "OnDeviceAiFacade(${profile.name})"

    private fun backendFor(profile: OnDeviceProfile): VoiceAiBackend = when (profile) {
        OnDeviceProfile.QWEN -> QwenOnDeviceBackend(appContext)
        OnDeviceProfile.GEMMA -> GemmaOnDeviceBackend(appContext)
    }

    private fun ensureBackend(): VoiceAiBackend {
        val desired = ModelManager.currentProfile(appContext)
        val current = active.get()
        if (current != null && current.profile == desired) return current
        synchronized(this) {
            val again = active.get()
            if (again != null && again.profile == desired) return again
            again?.release()
            val created = backendFor(desired)
            active.set(created)
            Log.d(TAG, "Switched backend to ${created.name} profile=$desired")
            return created
        }
    }

    fun switchProfile(profile: OnDeviceProfile) {
        ModelManager.setProfile(appContext, profile)
        synchronized(this) {
            active.getAndSet(null)?.release()
        }
        ModelManager.refresh(appContext)
        Log.d(TAG, "Profile set to $profile (backend will load on next use)")
    }

    override suspend fun ensureReady(): Result<Unit> = ensureBackend().ensureReady()

    override suspend fun transcribe(
        audioFile: File,
        vocabulary: List<AsrVocabularyTerm>
    ): Result<TranscriptionResult> {
        val backend = ensureBackend()
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

    override suspend fun chat(
        conversationHistory: List<ConversationTurn>,
        languageCode: String?
    ): Result<ChatResult> = ensureBackend().chat(conversationHistory, languageCode)

    override fun release() {
        synchronized(this) {
            active.getAndSet(null)?.release()
        }
    }

    private companion object {
        private const val TAG = "OnDeviceAiFacade"
    }
}
