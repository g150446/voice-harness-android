package com.g150446.voiceharness

import android.Manifest
import android.content.ActivityNotFoundException
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import java.util.Calendar
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
    val singleTapStatus by viewModel.singleTapStatus.collectAsState()
    val drivingMode by viewModel.drivingMode.collectAsState()
    val nodeDrivingMode by viewModel.nodeDrivingMode.collectAsState()
    val nodePendingDrivingMode by viewModel.nodePendingDrivingMode.collectAsState()
    val connectionPriority by viewModel.connectionPriority.collectAsState()
    val responseOutputTarget by viewModel.responseOutputTarget.collectAsState()
    val smartGlassesState by viewModel.smartGlassesState.collectAsState()
    val readingPassthroughEnabled by viewModel.readingPassthroughEnabled.collectAsState()
    val gestureCaptureEnabled by viewModel.gestureCaptureEnabled.collectAsState()
    val nodeGestureCaptureEnabled by viewModel.nodeGestureCaptureEnabled.collectAsState()
    val gestureDetectEnabled by viewModel.gestureDetectEnabled.collectAsState()
    val nodeGestureDetectEnabled by viewModel.nodeGestureDetectEnabled.collectAsState()
    val recordingCueEnabled by viewModel.recordingCueEnabled.collectAsState()
    val modelStatus by viewModel.modelStatus.collectAsState()
    val lastPipelineMs by viewModel.lastPipelineMs.collectAsState()
    val context = LocalContext.current
    val displayLocale = LocalConfiguration.current.locales[0]
    val scrollState = rememberScrollState()
    var isAssistant by remember { mutableStateOf(AssistantRoleManager.isHeld(context)) }
    var assistantSettingsError by remember { mutableStateOf<String?>(null) }
    var canDrawOverlay by remember {
        mutableStateOf(Settings.canDrawOverlays(context))
    }
    var accessibilityEnabled by remember {
        mutableStateOf(isRingAccessibilityEnabled(context))
    }
    var accessibilitySettingsError by remember { mutableStateOf<String?>(null) }
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
    val accessibilitySettingsLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        accessibilityEnabled = isRingAccessibilityEnabled(context)
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isAssistant = AssistantRoleManager.isHeld(context)
                canDrawOverlay = Settings.canDrawOverlays(context)
                accessibilityEnabled = isRingAccessibilityEnabled(context)
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
                    assistantSettingsError = null
                    try {
                        assistantRoleLauncher.launch(AssistantRoleManager.settingsIntent())
                    } catch (e: ActivityNotFoundException) {
                        Log.w(TAG, "Could not open voice assistant settings", e)
                        assistantSettingsError =
                            "設定画面を開けませんでした。Android設定からデフォルトのデジタルアシスタントを変更してください"
                    }
                },
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                Text("デフォルトのデジタルアシスタントアプリに設定")
            }
        } else {
            Text(
                text = "デフォルトの音声アシスタントに設定済み",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        assistantSettingsError?.let { message ->
            Text(
                text = message,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 8.dp),
            )
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
                text = "他アプリ表示中に録音・リーダーモード状態を画面に出すために必要です",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        if (accessibilityEnabled) {
            Text(
                text = "ユーザー補助: 有効（Kindle自動／ページ送り）",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        } else {
            OutlinedButton(
                onClick = {
                    accessibilitySettingsError = null
                    val intent = accessibilitySettingsIntent(context)
                    try {
                        accessibilitySettingsLauncher.launch(intent)
                    } catch (e: Exception) {
                        Log.w(TAG, "ActivityResult launch failed for accessibility settings", e)
                        try {
                            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (e2: Exception) {
                            Log.w(TAG, "Could not open accessibility settings", e2)
                            accessibilitySettingsError =
                                "設定画面を開けませんでした。Android設定→ユーザー補助から Voice Harness を有効にしてください"
                        }
                    }
                },
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                Text("ユーザー補助を有効にする")
            }
            Text(
                text = "リーダーモードのKindle自動表示とページめくりに必要です",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            accessibilitySettingsError?.let { message ->
                Text(
                    text = message,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
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
                text = when {
                    drivingMode == DrivingMode.DRIVING ->
                        "運転モード: シングルタップ録音（ホスト承認）"
                    gestureDetectEnabled ->
                        "通常モード: ジェスチャー / シングルタップ録音"
                    else ->
                        "通常モード: シングルタップ録音"
                },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 0x40 ack。Node の実効モードはアプリの意図と食い違いうるので、保留中と
            // 不一致のときだけ追記する（未確認・一致時は上の表示のまま変えない）。
            val pending = nodePendingDrivingMode
            val nodeMode = nodeDrivingMode
            if (pending != null) {
                Text(
                    text = if (pending == DrivingMode.DRIVING) "運転へ切替（録音終了後）"
                    else "通常へ切替（録音終了後）",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (nodeMode != null && nodeMode != drivingMode) {
                Text(
                    text = if (nodeMode == DrivingMode.DRIVING) "Node: 運転" else "Node: 通常",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        TapStatusLine(
            label = "シングルタップ",
            count = singleTapStatus.count,
            lastDetectedAtMillis = singleTapStatus.lastDetectedAtMillis,
            displayLocale = displayLocale,
        )
        TapStatusLine(
            label = "ダブルタップ",
            count = doubleTapStatus.count,
            lastDetectedAtMillis = doubleTapStatus.lastDetectedAtMillis,
            displayLocale = displayLocale,
            modifier = Modifier.padding(bottom = 12.dp),
        )

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
            Text(text = "G2", fontSize = 12.sp)
        }

        val glassesStatusText = when {
            !smartGlassesState.connected && !smartGlassesState.linked ->
                "G2プラグイン未接続（Even Hubで起動）"
            !smartGlassesState.connected -> "G2プラグインが応答していません"
            smartGlassesState.readingPassthroughActive -> "G2でリーダーモード中"
            smartGlassesState.displaying -> "G2に返答を表示中"
            else -> "G2プラグイン接続済み"
        }
        Text(
            text = smartGlassesState.errorMessage?.takeIf { it.isNotBlank() }
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
                text = "リーダーモード",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Switch(
                checked = readingPassthroughEnabled,
                onCheckedChange = viewModel::setReadingPassthroughEnabled,
            )
        }
        val readerModeStatus = when {
            !readingPassthroughEnabled && !smartGlassesState.connected ->
                "オフ — G2プラグイン接続中のみONできます"
            !readingPassthroughEnabled -> "オフ"
            !accessibilityEnabled ->
                "待機中 — ユーザー補助を有効にするとKindle自動表示が使えます（ダブルタップでも開始可）"
            smartGlassesState.readingPageLoading -> "次ページ取得中…"
            smartGlassesState.readingPassthroughActive ->
                "動作中 ${smartGlassesState.readingPage}/${smartGlassesState.readingPageCount}"
            else -> "待機中 — 電子書籍を開くか、Harness Nodeをダブルタップしてください"
        }
        Text(
            text = readerModeStatus,
            fontSize = 11.sp,
            color = when {
                !readingPassthroughEnabled -> MaterialTheme.colorScheme.onSurfaceVariant
                !accessibilityEnabled -> MaterialTheme.colorScheme.error
                else -> Color(0xFF43A047)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "ジェスチャー録音",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Switch(
                checked = gestureDetectEnabled,
                onCheckedChange = viewModel::setGestureDetectEnabled,
            )
        }
        val detectStatus = when {
            !gestureDetectEnabled -> "オフ — シングルタップのみ（誤認識防止）"
            nodeGestureDetectEnabled == null -> "オン（Node未確認・FW 0.0.95+ が必要）"
            nodeGestureDetectEnabled == true ->
                "オン — 手首ジェスチャーで録音開始/停止"
            else -> "Nodeがオフを報告（未対応ファームの可能性）"
        }
        Text(
            text = detectStatus,
            fontSize = 11.sp,
            color = if (nodeGestureDetectEnabled == false && gestureDetectEnabled) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "録音キュー音",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Switch(
                checked = recordingCueEnabled,
                onCheckedChange = viewModel::setRecordingCueEnabled,
            )
        }
        Text(
            text = if (recordingCueEnabled) {
                "オン — 開始・終了でビープ"
            } else {
                "オフ — 開始/終了音なし"
            },
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "ジェスチャーIMU収集",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Switch(
                checked = gestureCaptureEnabled,
                onCheckedChange = viewModel::setGestureCaptureEnabled,
            )
        }
        // The node holds this in RAM only, so show what it actually reported
        // rather than what we asked for; a node reset silently turns it off.
        val captureStatus = when {
            !gestureCaptureEnabled -> "オフ — 誤発火の学習データは貯まりません"
            nodeGestureCaptureEnabled == null -> "オン（Node未確認）"
            nodeGestureCaptureEnabled == true ->
                "オン — 1試行あたり約10KBを受信して履歴に保存します"
            else -> "Nodeがオフを報告（収集非対応ファームの可能性）"
        }
        Text(
            text = captureStatus,
            fontSize = 11.sp,
            color = if (nodeGestureCaptureEnabled == false && gestureCaptureEnabled) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
        )

        OutlinedButton(
            onClick = {
                val launchIntent = EVEN_REALITIES_APP_PACKAGES.firstNotNullOfOrNull { pkg ->
                    context.packageManager.getLaunchIntentForPackage(pkg)
                }
                val intent = launchIntent ?: Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("market://search?q=Even%20Realities")
                )
                try {
                    context.startActivity(intent)
                } catch (error: Exception) {
                    Log.w(TAG, "Could not open Even Realities App", error)
                    try {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://www.evenrealities.com/")
                            )
                        )
                    } catch (browserError: Exception) {
                        Log.e(TAG, "Could not open the Even Realities web page", browserError)
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            Text("Even Realities Appを開く")
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

private val EVEN_REALITIES_APP_PACKAGES = listOf(
    "com.evenrealities.even",
    "com.evenrealities.app",
    "com.evenrealities.evenapp",
)

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

        BulkGestureLabelRow(viewModel = viewModel)

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

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
                val gestureBadge = buildString {
                    if (entry.gestureDiags.isNotEmpty()) {
                        append(" · ジェスチャ${entry.gestureDiags.size}")
                    }
                    if (entry.trajectoryFile != null) append(" · 軌跡")
                    when (entry.gestureLabel) {
                        GestureLabel.INTENTIONAL -> append(" · 意図的")
                        GestureLabel.ACCIDENTAL -> append(" · 誤発火")
                        null -> Unit
                    }
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
        GestureLabelRow(entry = entry, viewModel = viewModel)

        Text(
            text = entry.trajectoryFile?.let { "IMU軌跡: $it" } ?: "IMU軌跡: なし（収集OFF）",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Only a node-side batch shares the trajectory's clock; say which one this
        // is, because learning from misaligned milestones fails silently.
        Text(
            text = if (entry.diagsFromNodeBatch) {
                "診断: Node バッチ（軌跡とは別時刻軸・末尾のみ）"
            } else {
                "診断: ライブ 0x30（全区間・軌跡とは別時刻軸）"
            },
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
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

/**
 * Labels a run of entries at once, by hour, for today.
 *
 * This mirrors how the classification actually happens: the user knows "the
 * clinic block this morning was all accidental" and would not open twenty
 * entries to say so. Without it the labels needed for training never arrive.
 */
@Composable
private fun BulkGestureLabelRow(
    viewModel: VoiceViewModel,
    modifier: Modifier = Modifier,
) {
    var fromHour by remember { mutableStateOf("10") }
    var toHour by remember { mutableStateOf("12") }
    var status by remember { mutableStateOf("") }

    fun apply(label: GestureLabel?) {
        val from = fromHour.toIntOrNull()
        val to = toHour.toIntOrNull()
        if (from == null || to == null || from !in 0..23 || to !in 0..24 || from >= to) {
            status = "時刻の指定が不正です"
            return
        }
        val day = Calendar.getInstance().apply {
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        day.set(Calendar.HOUR_OF_DAY, from)
        val fromMs = day.timeInMillis
        // Exclusive upper bound so 10-12 and 12-14 cannot both claim an entry.
        day.set(Calendar.HOUR_OF_DAY, 0)
        val toMs = day.timeInMillis + to.toLong() * 3_600_000L - 1L
        val count = viewModel.labelHistoryRange(fromMs, toMs, label)
        status = when {
            count == 0 -> "対象なし"
            label == null -> "${count}件の判定を取り消しました"
            label == GestureLabel.ACCIDENTAL -> "${count}件を誤発火にしました"
            else -> "${count}件を意図的にしました"
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "本日の時間帯を一括判定",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier.padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = fromHour,
                onValueChange = { fromHour = it.filter(Char::isDigit).take(2) },
                label = { Text("開始時", fontSize = 10.sp) },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                modifier = Modifier.width(84.dp),
            )
            Text(text = "〜", modifier = Modifier.padding(horizontal = 6.dp))
            OutlinedTextField(
                value = toHour,
                onValueChange = { toHour = it.filter(Char::isDigit).take(2) },
                label = { Text("終了時", fontSize = 10.sp) },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                modifier = Modifier.width(84.dp),
            )
        }
        Row(
            modifier = Modifier.padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { apply(GestureLabel.ACCIDENTAL) },
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) { Text("誤発火", fontSize = 12.sp) }
            Spacer(modifier = Modifier.width(6.dp))
            OutlinedButton(
                onClick = { apply(GestureLabel.INTENTIONAL) },
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) { Text("意図的", fontSize = 12.sp) }
            Spacer(modifier = Modifier.width(6.dp))
            TextButton(onClick = { apply(null) }) { Text("取消", fontSize = 12.sp) }
        }
        if (status.isNotEmpty()) {
            Text(
                text = status,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Training label for one attempt. Two explicit buttons rather than a single
 * toggle, so "not yet judged" stays distinguishable from "judged intentional" —
 * an unlabelled entry must never be counted as either class.
 */
@Composable
private fun GestureLabelRow(
    entry: HistoryEntry,
    viewModel: VoiceViewModel,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "判定:",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 6.dp),
        )
        GestureLabelButton(
            text = "意図的",
            selected = entry.gestureLabel == GestureLabel.INTENTIONAL,
            onClick = { viewModel.setGestureLabel(entry, GestureLabel.INTENTIONAL) },
        )
        Spacer(modifier = Modifier.width(6.dp))
        GestureLabelButton(
            text = "誤発火",
            selected = entry.gestureLabel == GestureLabel.ACCIDENTAL,
            onClick = { viewModel.setGestureLabel(entry, GestureLabel.ACCIDENTAL) },
        )
        if (entry.gestureLabel == null) {
            Text(
                text = "未判定",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun GestureLabelButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(onClick = onClick, contentPadding = PaddingValues(horizontal = 12.dp)) {
            Text(text, fontSize = 12.sp)
        }
    } else {
        OutlinedButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 12.dp)) {
            Text(text, fontSize = 12.sp)
        }
    }
}

@Composable
private fun TapStatusLine(
    label: String,
    count: Long,
    lastDetectedAtMillis: Long?,
    displayLocale: Locale,
    modifier: Modifier = Modifier,
) {
    val detail = lastDetectedAtMillis?.let { detectedAtMillis ->
        val detectedAt = SimpleDateFormat("HH:mm:ss", displayLocale)
            .format(Date(detectedAtMillis))
        "${count}回（$detectedAt）"
    } ?: "0回（未受信）"
    Text(
        text = "$label: $detail",
        fontSize = 12.sp,
        color = if (lastDetectedAtMillis != null) {
            Color(0xFF43A047)
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = modifier.fillMaxWidth(),
    )
}
