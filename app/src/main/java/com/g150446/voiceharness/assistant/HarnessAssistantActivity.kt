package com.g150446.voiceharness.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.g150446.voiceharness.ui.theme.HarnessVoiceTheme

/**
 * Bottom-sheet style assistant UI launched from VoiceInteractionSession.
 * Not retained in recents; reconnects to [AssistantSessionController] on recreation.
 */
class HarnessAssistantActivity : ComponentActivity() {
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startListening()
        else {
            AssistantSessionController.setListening(false)
            Log.w(TAG, "RECORD_AUDIO denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    closeAssistant()
                }
            },
        )
        setContent {
            HarnessVoiceTheme {
                val state by AssistantSessionController.uiState.collectAsState()
                LaunchedEffect(state.sessionActive) {
                    if (!state.sessionActive && !isFinishing) {
                        stopListeningInternal()
                        finish()
                    }
                }
                AssistantSheet(
                    state = state,
                    onClose = { closeAssistant() },
                    onOutside = { closeAssistant() },
                    onDraftChange = { AssistantSessionController.setDraftText(it) },
                    onToggleScreen = { AssistantSessionController.setUseScreenContext(it) },
                    onSendText = {
                        stopListeningInternal()
                        AssistantSessionController.submitText(
                            context = this,
                            text = state.draftText,
                            fromVoice = false,
                        )
                    },
                    onMic = { toggleMic() },
                )
            }
        }
    }

    override fun onDestroy() {
        stopListeningInternal()
        super.onDestroy()
    }

    private fun closeAssistant() {
        stopListeningInternal()
        AssistantSessionController.close(applicationContext)
        finish()
    }

    private fun toggleMic() {
        if (listening) {
            stopListeningInternal()
            AssistantSessionController.setListening(false)
            return
        }
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED -> startListening()
            else -> micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startListening() {
        val component = SpeechRecognizerResolver.resolveExternal(this)
        if (component == null) {
            Log.e(TAG, "No external RecognitionService")
            AssistantSessionController.onAssistantResult(
                requestId = null,
                conversationId = AssistantSessionController.uiState.value.conversationId,
                text = "",
                success = false,
                errorMessage = "音声認識サービスが見つかりません",
            )
            return
        }
        stopListeningInternal()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this, component).also { speech ->
            speech.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    listening = true
                    AssistantSessionController.setListening(true)
                }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    AssistantSessionController.setListening(false)
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    if (partial.isNotBlank()) {
                        AssistantSessionController.setPartialRecognition(partial)
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onError(error: Int) {
                    listening = false
                    AssistantSessionController.setListening(false)
                    Log.w(TAG, "Speech error=$error")
                }
                override fun onResults(results: Bundle?) {
                    listening = false
                    val query = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    AssistantSessionController.setListening(false)
                    if (query.isNotBlank()) {
                        AssistantSessionController.setDraftText(query)
                        AssistantSessionController.submitText(
                            context = this@HarnessAssistantActivity,
                            text = query,
                            fromVoice = true,
                        )
                    }
                    stopListeningInternal()
                }
            })
            speech.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }
            )
        }
    }

    private fun stopListeningInternal() {
        listening = false
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {
        }
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
    }

    private companion object {
        const val TAG = "HarnessAssistantAct"
    }
}

@Composable
private fun AssistantSheet(
    state: AssistantUiState,
    onClose: () -> Unit,
    onOutside: () -> Unit,
    onDraftChange: (String) -> Unit,
    onToggleScreen: (Boolean) -> Unit,
    onSendText: () -> Unit,
    onMic: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable { onOutside() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .clickable(enabled = false) { },
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Voice Harness", fontSize = 16.sp)
                        val src = state.sourceLabel ?: state.sourcePackage
                        if (!src.isNullOrBlank()) {
                            Text(src, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    TextButton(onClick = onClose) { Text("閉じる") }
                }

                if (!state.locked) {
                    FilterChip(
                        selected = state.useScreenContext,
                        onClick = {
                            if (state.screenAvailable) onToggleScreen(!state.useScreenContext)
                        },
                        enabled = state.screenAvailable,
                        label = {
                            Text(
                                if (!state.screenAvailable) "画面情報なし"
                                else if (state.useScreenContext) "画面を使用 ON"
                                else "画面を使用 OFF",
                            )
                        },
                    )
                }

                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(min = 80.dp, max = 220.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(state.messages, key = { it.id }) { msg ->
                        val label = if (msg.role == "user") "あなた" else "アシスタント"
                        Text("$label: ${msg.content}", fontSize = 13.sp)
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(state.statusText, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                state.errorMessage?.let {
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.draftText,
                    onValueChange = onDraftChange,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                    label = { Text("メッセージ") },
                    enabled = state.phase != AssistantPhase.GENERATING,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onMic,
                        enabled = state.phase != AssistantPhase.GENERATING,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            when (state.phase) {
                                AssistantPhase.LISTENING, AssistantPhase.RECOGNIZING -> "停止"
                                else -> "マイク"
                            }
                        )
                    }
                    Button(
                        onClick = onSendText,
                        enabled = state.draftText.isNotBlank() &&
                            state.phase != AssistantPhase.GENERATING &&
                            state.phase != AssistantPhase.LISTENING,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("送信")
                    }
                }
            }
        }
    }
}
