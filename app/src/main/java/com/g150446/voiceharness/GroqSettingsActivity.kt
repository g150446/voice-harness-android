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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.g150446.voiceharness.ui.theme.HarnessVoiceTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GroqSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ModelManager.migrateBackendSplitIfNeeded(this)
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
    var sttBackend by remember { mutableStateOf(ModelManager.currentSttBackend(context)) }
    var llmBackend by remember { mutableStateOf(ModelManager.currentLlmBackend(context)) }
    var openRouterModels by remember { mutableStateOf<List<OpenRouterModel>>(emptyList()) }
    var modelQuery by remember { mutableStateOf("") }
    var selectedOpenRouterModel by remember {
        mutableStateOf(OpenRouterPrefs.getModelId(context))
    }

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
        sttBackend = ModelManager.currentSttBackend(context)
        llmBackend = ModelManager.currentLlmBackend(context)
        val cached = OpenRouterPrefs.getModelsCacheJson(context)
        if (cached != null) {
            openRouterModels = OpenRouterModelCatalog.parseModelsJson(cached)
        }
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

        Text("音声認識 (ASR)", fontSize = 15.sp)
        SttBackendId.entries.forEach { backend ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = sttBackend == backend,
                        onClick = {
                            BleConnectionService.switchSttBackend(context, backend)
                            sttBackend = backend
                            ModelManager.refresh(context)
                            actionStatus = "ASR: ${backend.displayName}"
                        }
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = sttBackend == backend,
                    onClick = {
                        BleConnectionService.switchSttBackend(context, backend)
                        sttBackend = backend
                        ModelManager.refresh(context)
                        actionStatus = "ASR: ${backend.displayName}"
                    }
                )
                Text(backend.displayName, fontSize = 14.sp)
            }
        }
        Text(
            "ASR 状態: ${ModelManager.readinessLabel(status.readiness)} / Load ${status.lastLoadMs} ms / ASR ${status.lastAsrMs} ms",
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text("応答モデル (LLM)", fontSize = 15.sp)
        LlmBackendId.entries.forEach { backend ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = llmBackend == backend,
                        onClick = {
                            BleConnectionService.switchLlmBackend(context, backend)
                            llmBackend = backend
                            ModelManager.refresh(context)
                            actionStatus = "LLM: ${backend.displayName}"
                        }
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = llmBackend == backend,
                    onClick = {
                        BleConnectionService.switchLlmBackend(context, backend)
                        llmBackend = backend
                        ModelManager.refresh(context)
                        actionStatus = "LLM: ${backend.displayName}"
                    }
                )
                Text(backend.displayName, fontSize = 14.sp)
            }
        }
        Text(
            "LLM Chat: ${status.lastChatMs} ms / TTFT ${status.lastChatTtftMs} ms",
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp)
        )

        if (sttBackend == SttBackendId.GROQ || llmBackend == LlmBackendId.GROQ) {
            Spacer(modifier = Modifier.height(16.dp))
            Text("Groq API キー", fontSize = 14.sp)
            var apiKey by remember { mutableStateOf(GroqPrefs.getApiKey(context)) }
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("GROQ_API_KEY") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    GroqPrefs.setApiKey(context, apiKey)
                    ModelManager.refresh(context)
                    actionStatus = if (apiKey.isBlank()) "API キーをクリアしました" else "API キーを保存しました"
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Groq API キーを保存") }
        }

        if (llmBackend == LlmBackendId.OPENROUTER) {
            Spacer(modifier = Modifier.height(16.dp))
            Text("OpenRouter", fontSize = 14.sp)
            var orKey by remember { mutableStateOf(OpenRouterPrefs.getApiKey(context)) }
            OutlinedTextField(
                value = orKey,
                onValueChange = { orKey = it },
                label = { Text("OPENROUTER_API_KEY") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    OpenRouterPrefs.setApiKey(context, orKey)
                    actionStatus = if (orKey.isBlank()) {
                        "OpenRouter キーをクリアしました"
                    } else {
                        "OpenRouter キーを保存しました"
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("OpenRouter API キーを保存") }

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = modelQuery,
                onValueChange = { modelQuery = it },
                label = { Text("モデル検索") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    actionStatus = "モデル一覧を更新中..."
                    scope.launch {
                        val backend = OpenRouterLlmBackend(context.applicationContext)
                        val result = backend.fetchModels(forceRefresh = true)
                        if (result.isSuccess) {
                            openRouterModels = result.getOrThrow()
                            actionStatus = "モデル ${openRouterModels.size} 件を取得"
                        } else {
                            actionStatus = "更新失敗: ${result.exceptionOrNull()?.message}"
                            val cached = OpenRouterPrefs.getModelsCacheJson(context)
                            if (cached != null) {
                                openRouterModels = OpenRouterModelCatalog.parseModelsJson(cached)
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("モデル一覧を更新") }

            val filtered = OpenRouterModelCatalog.filter(openRouterModels, modelQuery).take(40)
            filtered.forEach { model ->
                val badges = buildList {
                    if (model.isFree) add("無料")
                    if (model.supportsImage) add("画像")
                    if (model.supportsTools) add("tools")
                }.joinToString(" · ")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = selectedOpenRouterModel == model.id,
                            onClick = {
                                selectedOpenRouterModel = model.id
                                OpenRouterPrefs.setModelId(context, model.id)
                                actionStatus = "選択: ${model.id}"
                            }
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedOpenRouterModel == model.id,
                        onClick = {
                            selectedOpenRouterModel = model.id
                            OpenRouterPrefs.setModelId(context, model.id)
                            actionStatus = "選択: ${model.id}"
                        }
                    )
                    Column {
                        Text(model.name, fontSize = 13.sp)
                        Text(
                            buildString {
                                append(model.id)
                                if (badges.isNotEmpty()) append("  [$badges]")
                            },
                            fontSize = 11.sp
                        )
                    }
                }
            }
            if (selectedOpenRouterModel.isNotBlank() &&
                openRouterModels.isNotEmpty() &&
                openRouterModels.none { it.id == selectedOpenRouterModel }
            ) {
                Text(
                    "選択中のモデルが一覧にありません。再選択してください。",
                    fontSize = 12.sp,
                    color = androidx.compose.ui.graphics.Color.Red
                )
            }
        }

        if (sttBackend == SttBackendId.GEMMA || sttBackend == SttBackendId.QWEN ||
            llmBackend == LlmBackendId.GEMMA || llmBackend == LlmBackendId.QWEN
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            Text("ASR ベース言語", fontSize = 14.sp)
            var speechBaseLanguage by remember {
                mutableStateOf(ModelManager.currentSpeechBaseLanguage(context))
            }
            SpeechBaseLanguage.entries.forEach { language ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = speechBaseLanguage == language,
                            onClick = {
                                ModelManager.setSpeechBaseLanguage(context, language)
                                speechBaseLanguage = language
                                actionStatus = "ASR ベース言語: ${language.displayName}"
                            }
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = speechBaseLanguage == language,
                        onClick = {
                            ModelManager.setSpeechBaseLanguage(context, language)
                            speechBaseLanguage = language
                            actionStatus = "ASR ベース言語: ${language.displayName}"
                        }
                    )
                    Column(modifier = Modifier.padding(start = 4.dp)) {
                        Text(language.displayName, fontSize = 14.sp)
                        Text(language.description, fontSize = 11.sp)
                    }
                }
            }

            if (sttBackend == SttBackendId.GEMMA || llmBackend == LlmBackendId.GEMMA) {
                Spacer(modifier = Modifier.height(16.dp))
                var mtpEnabled by remember {
                    mutableStateOf(ModelManager.isSpeculativeDecodingEnabled(context))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("MTP / 投機的デコード（Gemma）", fontSize = 14.sp)
                        Text("切り替えるとモデルを再読み込みします。", fontSize = 11.sp)
                    }
                    Switch(
                        checked = mtpEnabled,
                        onCheckedChange = { next ->
                            ModelManager.setSpeculativeDecodingEnabled(context, next)
                            mtpEnabled = next
                            BleConnectionService.reloadOnDeviceBackend(context)
                            actionStatus = if (next) "MTP ON" else "MTP OFF"
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        var debugPipelineTiming by remember {
            mutableStateOf(ModelManager.isDebugPipelineTimingEnabled(context))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("処理時間を表示（デバッグ）", fontSize = 14.sp)
            }
            Switch(
                checked = debugPipelineTiming,
                onCheckedChange = { next ->
                    ModelManager.setDebugPipelineTimingEnabled(context, next)
                    debugPipelineTiming = next
                }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(status.message, fontSize = 13.sp)
        if (!sttBackend.isCloud || !llmBackend.isCloud) {
            Spacer(modifier = Modifier.height(8.dp))
            Text("Gemma: ${slotLabel(status.gemma)}", fontSize = 12.sp)
            Text("LFM Chat: ${slotLabel(status.lfmChat)}", fontSize = 12.sp)
            Text("Qwen ASR decoder: ${slotLabel(status.qwenAsrDecoder)}", fontSize = 12.sp)
            Text("Qwen ASR projector: ${slotLabel(status.qwenAsrProjector)}", fontSize = 12.sp)
        }

        Spacer(modifier = Modifier.height(16.dp))
        if (!sttBackend.isCloud || llmBackend == LlmBackendId.GEMMA || llmBackend == LlmBackendId.QWEN) {
            Button(
                onClick = {
                    importSlot = when {
                        sttBackend == SttBackendId.GEMMA || llmBackend == LlmBackendId.GEMMA ->
                            ModelSlot.GEMMA
                        else -> ModelSlot.LFM_CHAT
                    }
                    pickModelLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                },
                enabled = !importing,
                modifier = Modifier.fillMaxWidth()
            ) { Text("モデルを取り込む") }

            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    actionStatus = "読み込み中..."
                    scope.launch {
                        ModelManager.refresh(context)
                        val facade = OnDeviceAiFacade(context.applicationContext)
                        val result = withContext(Dispatchers.IO) { facade.ensureReady() }
                        facade.release()
                        actionStatus = if (result.isSuccess) {
                            "準備確認OK（${ModelManager.status.value.lastLoadMs} ms）"
                        } else {
                            "失敗: ${result.exceptionOrNull()?.message ?: "不明なエラー"}"
                        }
                    }
                },
                enabled = canLoad || llmBackend == LlmBackendId.OPENROUTER ||
                    sttBackend == SttBackendId.GROQ || llmBackend == LlmBackendId.GROQ,
                modifier = Modifier.fillMaxWidth()
            ) { Text("モデルを読み込む / 接続確認") }
        }

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
