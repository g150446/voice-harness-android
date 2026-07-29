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

    override suspend fun transcribe(audioFile: File): Result<TranscriptionResult> =
        ensureBackend().transcribe(audioFile)

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
