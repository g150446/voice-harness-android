package com.g150446.voiceharness.assistant

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/** RecognitionService required by ROLE_ASSISTANT; delegates without recursing into itself. */
class HarnessRecognitionService : RecognitionService() {
    private var recognizer: SpeechRecognizer? = null

    override fun onStartListening(recognizerIntent: Intent, callback: Callback) {
        val component = SpeechRecognizerResolver.resolveExternal(this)
        if (component == null) {
            runCatching { callback.error(SpeechRecognizer.ERROR_CLIENT) }
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this, component).also { delegate ->
            delegate.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = forward { callback.readyForSpeech(params ?: Bundle()) }
                override fun onBeginningOfSpeech() = forward { callback.beginningOfSpeech() }
                override fun onRmsChanged(rmsdB: Float) = forward { callback.rmsChanged(rmsdB) }
                override fun onBufferReceived(buffer: ByteArray?) = forward { callback.bufferReceived(buffer ?: byteArrayOf()) }
                override fun onEndOfSpeech() = forward { callback.endOfSpeech() }
                override fun onError(error: Int) = forward { callback.error(error) }
                override fun onResults(results: Bundle?) = forward { callback.results(results ?: Bundle()) }
                override fun onPartialResults(partialResults: Bundle?) = forward { callback.partialResults(partialResults ?: Bundle()) }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            delegate.startListening(recognizerIntent)
        }
    }

    override fun onStopListening(callback: Callback) {
        recognizer?.stopListening()
    }

    override fun onCancel(callback: Callback) {
        recognizer?.cancel()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }

    private inline fun forward(block: () -> Unit) {
        runCatching(block)
    }
}
