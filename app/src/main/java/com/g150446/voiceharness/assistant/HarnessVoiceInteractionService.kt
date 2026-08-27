package com.g150446.voiceharness.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.util.Log
import com.g150446.voiceharness.BleConnectionService

/** System-owned entry point for the default digital-assistant role. */
class HarnessVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        instance = this
        OwnAppUiTracker.register(application)
        runCatching { BleConnectionService.start(this) }
            .onFailure { Log.e(TAG, "Unable to start assistant runtime", it) }
    }

    override fun onShutdown() {
        if (instance === this) instance = null
        super.onShutdown()
    }

    companion object {
        private const val TAG = "HarnessVoiceService"

        @Volatile
        private var instance: HarnessVoiceInteractionService? = null

        /**
         * Opens a headless session that requests assist structure + screenshot.
         * Returns false when the service is not ready (not default assistant / not bound).
         */
        fun requestHeadlessCapture(): Boolean {
            val service = instance ?: run {
                Log.d(TAG, "Headless capture: service not ready")
                return false
            }
            val args = Bundle().apply {
                putBoolean(HeadlessScreenCapture.ARG_HEADLESS, true)
            }
            val flags =
                VoiceInteractionSession.SHOW_WITH_ASSIST or
                    VoiceInteractionSession.SHOW_WITH_SCREENSHOT
            return runCatching {
                service.showSession(args, flags)
                true
            }.onFailure {
                Log.e(TAG, "Headless showSession failed", it)
            }.getOrDefault(false)
        }
    }
}
