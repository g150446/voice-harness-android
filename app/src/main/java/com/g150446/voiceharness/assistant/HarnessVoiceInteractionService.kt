package com.g150446.voiceharness.assistant

import android.service.voice.VoiceInteractionService
import android.util.Log
import com.g150446.voiceharness.BleConnectionService

/** System-owned, lightweight entry point for the default digital-assistant role. */
class HarnessVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        runCatching { BleConnectionService.start(this) }
            .onFailure { Log.e(TAG, "Unable to start assistant runtime", it) }
    }

    private companion object {
        const val TAG = "HarnessVoiceService"
    }
}
