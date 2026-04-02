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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.g150446.voiceharness.ui.theme.HarnessVoiceTheme

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

        if (hasBlePermissions()) {
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
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

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
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
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
    val connectionPriority by viewModel.connectionPriority.collectAsState()
    val context = LocalContext.current
    val scrollState = rememberScrollState()

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

        val statusText = when (state) {
            VoiceState.READY -> "Ready for BLE audio"
            VoiceState.RECORDING -> "Recording (BLE)..."
            VoiceState.TRANSCRIBING -> "Transcribing..."
            VoiceState.RESPONDING -> "Generating response..."
            VoiceState.SPEAKING -> "Speaking..."
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
            modifier = Modifier.padding(bottom = 16.dp)
        )

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
                    .padding(bottom = 24.dp)
            )
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

        OutlinedButton(
            onClick = {
                context.startActivity(Intent(context, GroqSettingsActivity::class.java))
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Settings")
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = { viewModel.openHistory() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("履歴")
        }
    }
}

@Composable
fun HistoryListScreen(
    modifier: Modifier = Modifier,
    viewModel: VoiceViewModel
) {
    BackHandler { viewModel.navigateBack() }

    val entries by viewModel.historyEntries.collectAsState()
    val dateFmt = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()) }
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
                        text = dateFmt.format(Date(entry.timestamp)),
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
    val dateFmt = remember { SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()) }
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
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
