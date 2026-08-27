package com.g150446.voiceharness.assistant

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log

/**
 * System assistant session.
 * - Interactive (power long-press): launches [HarnessAssistantActivity].
 * - Headless (HarnessNode voice): collects assist/screenshot only, no UI.
 */
class HarnessVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    private var headless = false

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        setUiEnabled(false)
        headless = args?.getBoolean(HeadlessScreenCapture.ARG_HEADLESS, false) == true
        super.onPrepareShow(args, showFlags)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        headless = headless ||
            args?.getBoolean(HeadlessScreenCapture.ARG_HEADLESS, false) == true
        Log.i(TAG, "Assistant session shown flags=$showFlags headless=$headless")
        setKeepAwake(true)
        if (headless) {
            HeadlessScreenCapture.onSessionShown {
                finishHeadless()
            }
            return
        }
        AssistantSessionController.beginSession(context) {
            finishSession()
        }
        startAssistantActivity()
    }

    override fun onHandleAssist(state: AssistState) {
        super.onHandleAssist(state)
        if (Build.VERSION.SDK_INT >= 29) {
            if (headless) {
                HeadlessScreenCapture.onHandleAssist(
                    data = state.assistData,
                    structure = state.assistStructure,
                )
            } else {
                AssistantSessionController.onHandleAssist(
                    data = state.assistData,
                    structure = state.assistStructure,
                    interaction = state.assistContent,
                )
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onHandleAssist(
        data: Bundle?,
        structure: AssistStructure?,
        content: AssistContent?,
    ) {
        @Suppress("DEPRECATION")
        super.onHandleAssist(data, structure, content)
        if (Build.VERSION.SDK_INT < 29) {
            if (headless) {
                HeadlessScreenCapture.onHandleAssist(data, structure)
            } else {
                AssistantSessionController.onHandleAssist(data, structure, content)
            }
        }
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        super.onHandleScreenshot(screenshot)
        if (headless) {
            HeadlessScreenCapture.onHandleScreenshot(screenshot)
        } else {
            AssistantSessionController.onHandleScreenshot(screenshot)
        }
    }

    override fun onHide() {
        Log.i(TAG, "Assistant session hide headless=$headless")
        super.onHide()
    }

    override fun onDestroy() {
        if (headless) {
            HeadlessScreenCapture.onSessionDestroyed()
        } else {
            AssistantSessionController.onSessionDestroyed(context.applicationContext)
        }
        setKeepAwake(false)
        super.onDestroy()
    }

    private fun startAssistantActivity() {
        val intent = Intent(context, HarnessAssistantActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                    Intent.FLAG_ACTIVITY_NO_HISTORY,
            )
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start assistant activity", e)
            finishSession()
        }
    }

    private fun finishSession() {
        setKeepAwake(false)
        hide()
    }

    private fun finishHeadless() {
        setKeepAwake(false)
        hide()
    }

    private companion object {
        const val TAG = "HarnessVoiceSession"
    }
}
