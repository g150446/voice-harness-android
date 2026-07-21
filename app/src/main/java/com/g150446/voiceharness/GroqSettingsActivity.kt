package com.g150446.voiceharness

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.g150446.voiceharness.ui.theme.HarnessVoiceTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GroqSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ModelManager.refresh(this)
        setContent {
            HarnessVoiceTheme {
                Scaffold { padding ->
                    ModelSettingsScreen(modifier = Modifier.padding(padding))
                }
            }
        }
    }
}

@Composable
private fun ModelSettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by ModelManager.status.collectAsState()
    var actionStatus by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var importSlot by remember { mutableStateOf<ModelSlot?>(null) }

    val pickModelLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            actionStatus = "キャンセルしました"
            return@rememberLauncherForActivityResult
        }
        importing = true
        actionStatus = "取り込み中..."
        val slot = importSlot
        scope.launch {
            val result = ModelManager.importFromUri(context.applicationContext, uri, slot)
            importing = false
            importSlot = null
            actionStatus = if (result.isSuccess) {
                val file = result.getOrThrow()
                "取り込み完了: ${file.name} (${ModelManager.formatSize(file.length())})"
            } else {
                "取り込み失敗: ${result.exceptionOrNull()?.message ?: "不明なエラー"}"
            }
        }
    }

    LaunchedEffect(Unit) {
        ModelManager.refresh(context)
    }

    val canLoad = !importing && status.readiness != ModelReadiness.MISSING &&
        status.readiness != ModelReadiness.LOADING

    Column(
        modifier = modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("モデル設定", fontSize = 18.sp)
        Spacer(modifier = Modifier.height(12.dp))

        Text("プロファイル", fontSize = 14.sp)
        OnDeviceProfile.entries.forEach { profile ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = status.profile == profile,
                        onClick = {
                            BleConnectionService.switchOnDeviceProfile(context, profile)
                            ModelManager.refresh(context)
                            actionStatus = "${profile.displayName} に切り替えました"
                        }
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = status.profile == profile,
                    onClick = {
                        BleConnectionService.switchOnDeviceProfile(context, profile)
                        ModelManager.refresh(context)
                        actionStatus = "${profile.displayName} に切り替えました"
                    }
                )
                Text(profile.displayName, fontSize = 14.sp)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "状態: ${ModelManager.readinessLabel(status.readiness)}（${status.profile.displayName}）",
            fontSize = 16.sp
        )
        Text(status.message, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))

        Spacer(modifier = Modifier.height(8.dp))
        Text("Gemma: ${slotLabel(status.gemma)}", fontSize = 12.sp)
        Text("Qwen LLM: ${slotLabel(status.qwenLlm)}", fontSize = 12.sp)
        Text("Qwen ASR decoder: ${slotLabel(status.qwenAsrDecoder)}", fontSize = 12.sp)
        Text("Qwen ASR projector: ${slotLabel(status.qwenAsrProjector)}", fontSize = 12.sp)

        if (status.lastLoadMs > 0 || status.lastAsrMs > 0 || status.lastChatMs > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            if (status.lastLoadMs > 0) Text("Load: ${status.lastLoadMs} ms", fontSize = 12.sp)
            if (status.lastAsrMs > 0) Text("ASR: ${status.lastAsrMs} ms", fontSize = 12.sp)
            if (status.lastChatMs > 0) Text("Chat: ${status.lastChatMs} ms", fontSize = 12.sp)
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                importSlot = when (status.profile) {
                    OnDeviceProfile.GEMMA -> ModelSlot.GEMMA
                    OnDeviceProfile.QWEN -> ModelSlot.QWEN_LLM
                }
                pickModelLauncher.launch(arrayOf("application/octet-stream", "*/*"))
            },
            enabled = !importing,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (status.profile == OnDeviceProfile.GEMMA) {
                    "Gemma を取り込む"
                } else {
                    "Qwen LLM を取り込む"
                }
            )
        }

        if (status.profile == OnDeviceProfile.QWEN) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    importSlot = ModelSlot.QWEN_ASR_DECODER
                    pickModelLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                },
                enabled = !importing,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Qwen ASR decoder を取り込む") }

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    importSlot = ModelSlot.QWEN_ASR_PROJECTOR
                    pickModelLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                },
                enabled = !importing,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Qwen ASR projector を取り込む") }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = {
                actionStatus = "読み込み中..."
                scope.launch {
                    ModelManager.refresh(context)
                    val backend: VoiceAiBackend = when (ModelManager.currentProfile(context)) {
                        OnDeviceProfile.QWEN -> QwenOnDeviceBackend(context.applicationContext)
                        OnDeviceProfile.GEMMA -> GemmaOnDeviceBackend(context.applicationContext)
                    }
                    val result = withContext(Dispatchers.IO) { backend.ensureReady() }
                    // Don't keep a second engine if service already has one; release probe engine.
                    backend.release()
                    // Service facade will load on next use; force service switch to sync.
                    BleConnectionService.switchOnDeviceProfile(
                        context,
                        ModelManager.currentProfile(context)
                    )
                    actionStatus = if (result.isSuccess) {
                        "準備確認OK（${ModelManager.status.value.lastLoadMs} ms）"
                    } else {
                        "失敗: ${result.exceptionOrNull()?.message ?: "不明なエラー"}"
                    }
                }
            },
            enabled = canLoad,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("モデルを読み込む")
        }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                ModelManager.refresh(context)
                actionStatus = statusSummary(ModelManager.status.value)
            },
            enabled = !importing,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("再スキャン")
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "デフォルトは高速 (Qwen)。高品質が必要なときだけ Gemma に切替。\n" +
                "パス入力は不要です。",
            fontSize = 12.sp
        )

        if (actionStatus.isNotEmpty()) {
            Text(actionStatus, modifier = Modifier.padding(top = 12.dp), fontSize = 13.sp)
        }
    }
}

private fun slotLabel(slot: SlotStatus): String {
    val state = ModelManager.readinessLabel(slot.readiness)
    val name = slot.fileName ?: "-"
    val size = if (slot.sizeBytes > 0) ModelManager.formatSize(slot.sizeBytes) else "-"
    return "$state ($name, $size)"
}

private fun statusSummary(status: ModelStatus): String =
    "profile=${status.profile} readiness=${status.readiness} ${status.message}"
