package com.g150446.voiceharness.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.service.voice.VoiceInteractionSession
import androidx.core.content.ContextCompat
import com.g150446.voiceharness.BleConnectionService
import java.util.UUID

/** Voice-only assistant surface. It never launches an Activity, including while locked. */
class HarnessVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    private val conversationId = "digital-assistant-${UUID.randomUUID()}"
    private var recognizer: SpeechRecognizer? = null

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        setUiEnabled(false)
        super.onPrepareShow(args, showFlags)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        setKeepAwake(true)
        startListening()
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            finishHeadlessSession()
            return
        }
        val component = SpeechRecognizerResolver.resolveExternal(context)
        if (component == null) {
            finishHeadlessSession()
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context, component).also { speech ->
            speech.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onError(error: Int) = finishHeadlessSession()
                override fun onResults(results: Bundle?) {
                    val query = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    if (query.isNotBlank()) {
                        BleConnectionService.submitAssistantText(context, query, conversationId)
                    }
                    finishHeadlessSession()
                }
            })
            speech.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                }
            )
        }
    }

    private fun finishHeadlessSession() {
        recognizer?.destroy()
        recognizer = null
        setKeepAwake(false)
        hide()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }
}
