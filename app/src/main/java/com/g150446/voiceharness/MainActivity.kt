package com.g150446.voiceharness

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.g150446.voiceharness.ui.theme.HarnessVoiceTheme
import com.g150446.voiceharness.assistant.AssistantRoleManager

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {

    private val voiceViewModel: VoiceViewModel by viewModels()

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasBlePermissions()) {
            BleConnectionService.start(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (hasRequiredPermissions()) {
            BleConnectionService.start(this)
        } else {
            requestAllPermissions()
        }

        requestBatteryOptimizationExemption()

        setContent {
            HarnessVoiceTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    VoiceScreen(
                        modifier = Modifier.padding(innerPadding),
                        viewModel = voiceViewModel
                    )
                }
            }
        }
    }

    private fun hasBlePermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun hasRequiredPermissions(): Boolean = hasBlePermissions() &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("battery_opt_requested", false)) return
        prefs.edit().putBoolean("battery_opt_requested", true).apply()
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not request battery optimization exemption", e)
        }
    }

    private fun requestAllPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.RECORD_AUDIO,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions += Manifest.permission.ACTIVITY_RECOGNITION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        requestPermissionsLauncher.launch(permissions.toTypedArray())
    }
}

@Composable
fun VoiceScreen(
    modifier: Modifier = Modifier,
    viewModel: VoiceViewModel = viewModel()
) {
    val currentScreen by viewModel.currentScreen.collectAsState()

    key(currentScreen) {
        when (currentScreen) {
            AppScreen.HOME -> HomeScreen(modifier = modifier, viewModel = viewModel)
            AppScreen.HISTORY_LIST -> HistoryListScreen(modifier = modifier, viewModel = viewModel)
            AppScreen.HISTORY_DETAIL -> HistoryDetailScreen(modifier = modifier, viewModel = viewModel)
            AppScreen.REMINDER_LIST -> ReminderListScreen(modifier = modifier, viewModel = viewModel)
            AppScreen.GESTURE_DIAG -> GestureDiagScreen(modifier = modifier, viewModel = viewModel)
        }
    }
}

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    viewModel: VoiceViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val transcription by viewModel.transcription.collectAsState()
    val response by viewModel.response.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val bleConnectionState by viewModel.bleConnectionState.collectAsState()
    val bleMode by viewModel.bleMode.collectAsState()
    val availableBleDevices by viewModel.availableBleDevices.collectAsState()
    val selectedBleDeviceAddress by viewModel.selectedBleDeviceAddress.collectAsState()
    val preferredBleDevice by viewModel.preferredBleDevice.collectAsState()
    val batteryLevel by viewModel.batteryLevel.collectAsState()
    val isPrimary by viewModel.isPrimary.collectAsState()
    val doubleTapStatus by viewModel.doubleTapStatus.collectAsState()
    val drivingMode by viewModel.drivingMode.collectAsState()
    val connectionPriority by viewModel.connectionPriority.collectAsState()
    val responseOutputTarget by viewModel.responseOutputTarget.collectAsState()
    val smartGlassesState by viewModel.smartGlassesState.collectAsState()
    val readingPassthroughEnabled by viewModel.readingPassthroughEnabled.collectAsState()
    val modelStatus by viewModel.modelStatus.collectAsState()
    val lastPipelineMs by viewModel.lastPipelineMs.collectAsState()
    val context = LocalContext.current
    val displayLocale = LocalConfiguration.current.locales[0]
    val scrollState = rememberScrollState()
    var isAssistant by remember { mutableStateOf(AssistantRoleManager.isHeld(context)) }
    var canDrawOverlay by remember {
        mutableStateOf(Settings.canDrawOverlays(context))
    }
    val assistantRoleLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        isAssistant = AssistantRoleManager.isHeld(context)
    }
    val overlayPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        canDrawOverlay = Settings.canDrawOverlays(context)
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isAssistant = AssistantRoleManager.isHeld(context)
                canDrawOverlay = Settings.canDrawOverlays(context)
            }
        }
        val owner = context as? androidx.lifecycle.LifecycleOwner
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Text(
            text = "Voice Harness",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
        )

        if (!isAssistant) {
            OutlinedButton(
                onClick = {
                    val intent = if (AssistantRoleManager.isAvailable(context)) {
                        AssistantRoleManager.requestIntent(context)
                    } else {
                        AssistantRoleManager.fallbackSettingsIntent()
                    }
                    assistantRoleLauncher.launch(intent)
                },
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                Text("デフォルトの音声アシスタントに設定")
            }
        }

        if (!canDrawOverlay) {
            OutlinedButton(
                onClick = {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    )
                    overlayPermissionLauncher.launch(intent)
                },
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                Text("状態オーバーレイの表示を許可")
            }
            Text(
                text = "他アプリ表示中に録音・パススルー状態を画面に出すために必要です",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        val (dotColor, bleLabel) = when (bleConnectionState) {
            BleConnectionState.CONNECTED -> Color(0xFF43A047) to "BLE Connected"
            BleConnectionState.CONNECTING -> Color(0xFFFFA726) to "BLE Connecting..."
            BleConnectionState.SCANNING -> Color(0xFF42A5F5) to "BLE Scanning..."
            BleConnectionState.DISCONNECTED -> Color(0xFF9E9E9E) to "BLE Disconnected"
        }
        Row(
            modifier = Modifier.padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Canvas(modifier = Modifier.size(8.dp)) { drawCircle(color = dotColor) }
            Text(text = bleLabel, fontSize = 12.sp, color = dotColor)
            if (bleConnectionState == BleConnectionState.CONNECTED) {
                val batteryText = batteryLevel?.let { "$it%" } ?: "..."
                Text(text = batteryText, fontSize = 12.sp, color = dotColor)
            }
            if (bleMode && state == VoiceState.RECORDING) {
                Text(text = "(nRF52840 recording)", fontSize = 11.sp, color = Color(0xFF9E9E9E))
            }
            Text(
                text = if (drivingMode == DrivingMode.DRIVING) "運転モード: ダブルタップ録音" else "通常モード: ジェスチャー録音",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        doubleTapStatus.lastDetectedAtMillis?.let { detectedAtMillis ->
            val detectedAt = SimpleDateFormat("HH:mm:ss", displayLocale)
                .format(Date(detectedAtMillis))
            Text(
                text = "ダブルタップ受信: ${doubleTapStatus.count}回（$detectedAt）",
                fontSize = 12.sp,
                color = Color(0xFF43A047),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            )
        }

        preferredBleDevice?.let { device ->
            Text(
                text = "Preferred device: ${device.name}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            )
        }

        val profileLabel = modelStatus.profile.displayName
        val coldStart = modelStatus.readiness == ModelReadiness.LOADING ||
            modelStatus.readiness == ModelReadiness.FOUND
        val statusText = when (state) {
            VoiceState.READY -> when (modelStatus.readiness) {
                ModelReadiness.READY -> "準備完了（$profileLabel）"
                ModelReadiness.LOADING -> "モデル読み込み中（$profileLabel）…\n初回のため返事が遅くなります"
                ModelReadiness.FOUND -> "検出済み（$profileLabel）\n初回の応答はモデル読み込みのため遅くなります"
                ModelReadiness.MISSING -> "モデル未検出 — 設定を開く"
                ModelReadiness.ERROR -> "モデルエラー — 設定を開く"
            }
            VoiceState.RECORDING -> "Recording (BLE)..."
            VoiceState.TRANSCRIBING -> if (coldStart) {
                "文字起こし中（$profileLabel）…\n初回はモデル読み込みのため時間がかかります"
            } else {
                "文字起こし中（$profileLabel）..."
            }
            VoiceState.RESPONDING -> if (coldStart || modelStatus.lastLoadMs > 30_000L && modelStatus.lastChatMs == 0L) {
                "応答生成中（$profileLabel）…\n初回は遅くなることがあります"
            } else {
                "応答生成中（$profileLabel）..."
            }
            VoiceState.SPEAKING -> "読み上げ中..."
            VoiceState.ERROR -> "Error"
        }
        val statusColor = when (state) {
            VoiceState.RECORDING -> Color(0xFFE53935)
            VoiceState.ERROR -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.primary
        }
        Text(
            text = statusText,
            color = statusColor,
            fontSize = 16.sp,
            modifier = Modifier.padding(bottom = if (coldStart && state == VoiceState.READY) 8.dp else 16.dp)
        )
        if (coldStart && state == VoiceState.READY) {
            Text(
                text = "最初の発話後にモデルを読み込みます（目安 30〜60 秒）。2回目以降は速くなります。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            )
        }

        if (errorMessage.isNotEmpty()) {
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            )
        }

        if (transcription.isNotEmpty()) {
            Text(
                text = "あなた",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
            )
            Text(
                text = transcription,
                fontSize = 15.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            )
        }

        if (response.isNotEmpty()) {
            Text(
                text = "AI",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
            )
            Text(
                text = response,
                fontSize = 15.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        bottom = if (
                            modelStatus.debugPipelineTimingEnabled && lastPipelineMs > 0L
                        ) 8.dp else 24.dp
                    )
            )
            if (modelStatus.debugPipelineTimingEnabled && lastPipelineMs > 0L) {
                Text(
                    text = formatPipelineTiming(lastPipelineMs),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp)
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(bottom = 12.dp))

        Text(
            text = "AI返答の出力先",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "音声", fontSize = 12.sp)
            Switch(
                checked = responseOutputTarget == ResponseOutputTarget.SMART_GLASSES,
                onCheckedChange = { useSmartGlasses ->
                    viewModel.setResponseOutputTarget(
                        if (useSmartGlasses) {
                            ResponseOutputTarget.SMART_GLASSES
                        } else {
                            ResponseOutputTarget.PHONE_AUDIO
                        }
                    )
                },
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            Text(text = "Z100", fontSize = 12.sp)
        }

        val glassesStatusText = when {
            !smartGlassesState.available -> "Vuzix Connectを利用できません"
            !smartGlassesState.linked -> "Z100は未リンクです"
            !smartGlassesState.connected -> "Z100は未接続です"
            smartGlassesState.readingPassthroughActive ->
                "読書パススルー ${smartGlassesState.readingPage}/${smartGlassesState.readingPageCount}"
            smartGlassesState.displaying -> "Z100に返答を表示中"
            smartGlassesState.controlledByMe -> "Z100接続済み（制御中）"
            else -> "Z100接続済み"
        }
        Text(
            text = smartGlassesState.deviceName?.let { "$glassesStatusText: $it" }
                ?: glassesStatusText,
            fontSize = 11.sp,
            color = if (smartGlassesState.connected) {
                Color(0xFF43A047)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "パススルーモード",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Switch(
                checked = readingPassthroughEnabled,
                onCheckedChange = viewModel::setReadingPassthroughEnabled,
            )
        }
        val passthroughStatus = when {
            !readingPassthroughEnabled -> "オフ"
            smartGlassesState.readingPageLoading -> "次ページ取得中…"
            smartGlassesState.readingPassthroughActive ->
                "動作中 ${smartGlassesState.readingPage}/${smartGlassesState.readingPageCount}"
            else -> "待機中 — 電子書籍を開いてHarness Nodeの起動ジェスチャーを行ってください"
        }
        Text(
            text = passthroughStatus,
            fontSize = 11.sp,
            color = if (readingPassthroughEnabled) {
                Color(0xFF43A047)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
        )

        OutlinedButton(
            onClick = {
                val launchIntent = context.packageManager
                    .getLaunchIntentForPackage(VUZIX_CONNECT_PACKAGE)
                val intent = launchIntent ?: Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=$VUZIX_CONNECT_PACKAGE")
                )
                try {
                    context.startActivity(intent)
                } catch (error: Exception) {
                    Log.w(TAG, "Could not open Vuzix Connect", error)
                    try {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(
                                    "https://play.google.com/store/apps/details?id=$VUZIX_CONNECT_PACKAGE"
                                )
                            )
                        )
                    } catch (browserError: Exception) {
                        Log.e(TAG, "Could not open the Vuzix Connect web page", browserError)
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            Text("Vuzix Connectを開く")
        }

        Text(
            text = if (bleConnectionState == BleConnectionState.CONNECTED) {
                "BLE デバイスのジェスチャーから録音が始まります"
            } else {
                "音声入力は BLE デバイスのみです。Scan して接続してください"
            },
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = viewModel::startBleScan,
                enabled = bleConnectionState != BleConnectionState.CONNECTING,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (bleConnectionState == BleConnectionState.SCANNING) "Scanning..." else "Scan devices")
            }
            Button(
                onClick = viewModel::connectSelectedBleDevice,
                enabled = selectedBleDeviceAddress != null &&
                    bleConnectionState != BleConnectionState.CONNECTED &&
                    bleConnectionState != BleConnectionState.CONNECTING,
                modifier = Modifier.weight(1f)
            ) {
                Text("Connect")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = viewModel::disconnectBleDevice,
            enabled = bleConnectionState == BleConnectionState.CONNECTED ||
                bleConnectionState == BleConnectionState.CONNECTING,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Disconnect")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Discovered devices",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        )

        if (availableBleDevices.isEmpty()) {
            Text(
                text = if (bleConnectionState == BleConnectionState.SCANNING) {
                    "Searching for BLE devices..."
                } else {
                    "No BLE devices found yet"
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            )
        } else {
            availableBleDevices.forEach { device ->
                val isSelected = device.address == selectedBleDeviceAddress
                val interactionSource = remember(device.address) { MutableInteractionSource() }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null
                        ) {
                            viewModel.selectBleDevice(device.address)
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = { viewModel.selectBleDevice(device.address) }
                    )
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = device.name, fontSize = 14.sp)
                        Text(
                            text = device.address,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "優先接続: ",
                modifier = Modifier.weight(1f)
            )
            Text(text = "Mac Handy", fontSize = 12.sp)
            Switch(
                checked = connectionPriority == ConnectionPriority.ANDROID,
                onCheckedChange = { isAndroid ->
                    viewModel.setConnectionPriority(
                        if (isAndroid) ConnectionPriority.ANDROID else ConnectionPriority.MAC_HANDY
                    )
                },
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            Text(text = "Android", fontSize = 12.sp)
        }
        if (bleConnectionState == BleConnectionState.CONNECTED) {
            Text(
                text = if (isPrimary) "Audio: このデバイスが受信中" else "Audio: Mac Handyが受信中",
                fontSize = 11.sp,
                color = if (isPrimary) Color(0xFF43A047) else Color(0xFFFFA726),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Text(
            text = "モデル: ${modelStatus.profile.displayName} / ${ModelManager.readinessLabel(modelStatus.readiness)}" +
                (modelStatus.modelFileName?.let { " ($it)" } ?: "") +
                if (
                    modelStatus.debugPipelineTimingEnabled &&
                    (modelStatus.lastAsrMs > 0 || modelStatus.lastChatMs > 0)
                ) {
                    "  ASR ${modelStatus.lastAsrMs}ms / Chat ${modelStatus.lastChatMs}ms" +
                        if (modelStatus.lastChatTtftMs > 0) {
                            " / TTFT ${modelStatus.lastChatTtftMs}ms"
                        } else {
                            ""
                        }
                } else {
                    ""
                },
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        )

        OutlinedButton(
            onClick = {
                context.startActivity(Intent(context, GroqSettingsActivity::class.java))
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("モデル設定")
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = { viewModel.openReminders() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("リマインダー")
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = { viewModel.openHistory() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("履歴")
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = { viewModel.openGestureDiag() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("ジェスチャ診断")
        }
    }
}

@Composable
fun GestureDiagScreen(
    modifier: Modifier = Modifier,
    viewModel: VoiceViewModel
) {
    BackHandler { viewModel.navigateBack() }

    val live by GestureDiagStore.liveEntries.collectAsState()
    val sessions by GestureDiagStore.sessions.collectAsState()
    val status by GestureDiagStore.statusLine.collectAsState()
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { viewModel.navigateBack() }) {
                Text("戻る")
            }
            Text(
                text = "ジェスチャ診断",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { viewModel.clearGestureDiag() }) {
                Text("クリア")
            }
        }

        Text(
            text = "FW の 0x30（ライブ）と 0x33–0x35（録音終了バッチ）。" +
                "履歴バッチは GESTURE_DEBUG_HISTORY=1 の OTA のみ。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (status.isNotEmpty()) {
            Text(
                text = status,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        Text(
            text = "バッチセッション (${sessions.size})",
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        if (sessions.isEmpty()) {
            Text(
                text = "（まだバッチなし）",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        } else {
            sessions.take(5).forEach { session ->
                Text(
                    text = "session=${session.sessionId} entries=${session.entries.size}" +
                        if (session.complete) " OK" else " partial",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp)
                )
                session.entries.takeLast(12).forEach { e ->
                    Text(
                        text = e.summaryLine(),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        Text(
            text = "ライブ / 蓄積 (${live.size})",
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        live.takeLast(40).asReversed().forEach { e ->
            Text(
                text = e.summaryLine(),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 1.dp)
            )
        }
    }
}

private const val VUZIX_CONNECT_PACKAGE = "com.vuzix.connect"

@Composable
fun HistoryListScreen(
    modifier: Modifier = Modifier,
    viewModel: VoiceViewModel
) {
    BackHandler { viewModel.navigateBack() }

    val entries by viewModel.historyEntries.collectAsState()
    val historyLocale = LocalConfiguration.current.locales[0]
    val dateFmt = remember(historyLocale) { SimpleDateFormat("yyyy/MM/dd HH:mm", historyLocale) }
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { viewModel.navigateBack() }) {
                Text("← 戻る")
            }
            Text(
                text = "履歴",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))

        if (entries.isEmpty()) {
            Text(
                text = "履歴はまだありません",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 32.dp)
            )
                } else {
            entries.forEach { entry ->
                val previewText = when {
                    entry.isSilent -> "（無音）"
                    entry.errorMessage.isNotEmpty() -> "[エラー] ${entry.errorMessage.take(40)}"
                    entry.transcription.length > 60 -> entry.transcription.take(60) + "…"
                    else -> entry.transcription
                }
                val gestureBadge = if (entry.gestureDiags.isNotEmpty()) {
                    " · ジェスチャ${entry.gestureDiags.size}"
                } else {
                    ""
                }
                val interactionSource = remember(entry.id) { MutableInteractionSource() }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null
                        ) { viewModel.openHistoryDetail(entry) }
                        .padding(vertical = 12.dp)
                ) {
                    Text(
                        text = dateFmt.format(Date(entry.timestamp)) + gestureBadge,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = previewText,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun HistoryDetailScreen(
    modifier: Modifier = Modifier,
    viewModel: VoiceViewModel
) {
    BackHandler { viewModel.navigateBack() }

    val entry = viewModel.selectedHistoryEntry.collectAsState().value ?: return
    val historyLocale = LocalConfiguration.current.locales[0]
    val dateFmt = remember(historyLocale) { SimpleDateFormat("yyyy/MM/dd HH:mm:ss", historyLocale) }
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Top
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { viewModel.navigateBack() }) {
                Text("← 戻る")
            }
            Text(
                text = dateFmt.format(Date(entry.timestamp)),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        HorizontalDivider(modifier = Modifier.padding(bottom = 16.dp))

        if (entry.isSilent) {
            Text(
                text = "（音声なし）",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        if (entry.transcription.isNotEmpty()) {
            Text(
                text = "あなた",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = entry.transcription,
                fontSize = 15.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            )
        }

        if (entry.response.isNotEmpty()) {
            Text(
                text = "AI",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = entry.response,
                fontSize = 15.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            )
        }

        if (entry.errorMessage.isNotEmpty()) {
            Text(
                text = "エラー",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = entry.errorMessage,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Text(
            text = "ジェスチャ判定",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        if (entry.gestureDiags.isEmpty()) {
            Text(
                text = "（診断データなし）",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = "${entry.gestureDiags.size} 件（実測と閾値）",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            entry.gestureDiags.forEach { diag ->
                Text(
                    text = diag.historyDetailLine(),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                )
            }
        }
    }
}
