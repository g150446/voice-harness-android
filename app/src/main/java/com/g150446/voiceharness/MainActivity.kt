package com.g150446.voiceharness

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.g150446.voiceharness.ui.theme.HarnessVoiceTheme

class MainActivity : ComponentActivity() {

    private val voiceViewModel: VoiceViewModel by viewModels()

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        if (recordGranted) {
            // BLE service is started separately; just check if we should start recording
        }
        // Start BLE service if BLE permissions are now granted
        if (hasBlePermissions()) {
            BleConnectionService.start(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Start BLE service if we have permissions
        if (hasBlePermissions()) {
            BleConnectionService.start(this)
        } else {
            requestAllPermissions()
        }

        setContent {
            HarnessVoiceTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    VoiceScreen(
                        modifier = Modifier.padding(innerPadding),
                        viewModel = voiceViewModel,
                        onRecordClick = { handleRecordButtonClick() }
                    )
                }
            }
        }
    }

    private fun handleRecordButtonClick() {
        val state = voiceViewModel.state.value
        when (state) {
            VoiceState.RECORDING -> voiceViewModel.stopRecordingAndProcess()
            VoiceState.SPEAKING -> {
                voiceViewModel.stopSpeaking()
                voiceViewModel.startRecording()
            }
            VoiceState.READY, VoiceState.ERROR -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    voiceViewModel.startRecording()
                } else {
                    requestAllPermissions()
                }
            }
            else -> {} // ignore taps while transcribing/responding
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

    private fun requestAllPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissionsLauncher.launch(perms.toTypedArray())
    }
}

@Composable
fun VoiceScreen(
    modifier: Modifier = Modifier,
    viewModel: VoiceViewModel = viewModel(),
    onRecordClick: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val transcription by viewModel.transcription.collectAsState()
    val response by viewModel.response.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val bleConnectionState by viewModel.bleConnectionState.collectAsState()
    val bleMode by viewModel.bleMode.collectAsState()
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

        // BLE connection status dot
        val (dotColor, bleLabel) = when (bleConnectionState) {
            BleConnectionState.CONNECTED -> Color(0xFF43A047) to "BLE Connected"
            BleConnectionState.CONNECTING -> Color(0xFFFFA726) to "BLE Connecting..."
            BleConnectionState.SCANNING -> Color(0xFF42A5F5) to "BLE Scanning..."
            BleConnectionState.DISCONNECTED -> Color(0xFF9E9E9E) to "BLE Off"
        }
        Row(
            modifier = Modifier.padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Canvas(modifier = Modifier.size(8.dp)) { drawCircle(color = dotColor) }
            Text(text = bleLabel, fontSize = 12.sp, color = dotColor)
            if (bleMode && state == VoiceState.RECORDING) {
                Text(text = "  (nRF52840)", fontSize = 11.sp, color = Color(0xFF9E9E9E))
            }
        }

        // Status indicator
        val statusText = when (state) {
            VoiceState.READY -> "Ready"
            VoiceState.RECORDING -> if (bleMode) "Recording (BLE)..." else "Recording..."
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

        // Error message
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

        // Transcription
        if (transcription.isNotEmpty()) {
            Text(
                text = "あなた",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
            )
            Text(
                text = transcription,
                fontSize = 15.sp,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            )
        }

        // AI response
        if (response.isNotEmpty()) {
            Text(
                text = "AI",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
            )
            Text(
                text = response,
                fontSize = 15.sp,
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Record button (phone mic; disabled when BLE recording is active)
        val buttonLabel = when {
            state == VoiceState.RECORDING && bleMode -> "■  Stop BLE Recording"
            state == VoiceState.RECORDING -> "■  Stop"
            state == VoiceState.SPEAKING -> "■  Stop Speaking"
            state == VoiceState.TRANSCRIBING || state == VoiceState.RESPONDING -> "..."
            else -> "●  Record (Mic)"
        }
        val buttonEnabled = state == VoiceState.READY ||
            state == VoiceState.RECORDING ||
            state == VoiceState.SPEAKING ||
            state == VoiceState.ERROR
        val buttonColor = if (state == VoiceState.RECORDING) Color(0xFFE53935)
        else MaterialTheme.colorScheme.primary

        Button(
            onClick = onRecordClick,
            enabled = buttonEnabled,
            colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text(buttonLabel, fontSize = 16.sp)
        }

        if (bleConnectionState == BleConnectionState.CONNECTED) {
            Text(
                text = "ジェスチャーでも録音開始できます",
                fontSize = 11.sp,
                color = Color(0xFF9E9E9E),
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = {
                context.startActivity(Intent(context, GroqSettingsActivity::class.java))
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Settings")
        }
    }
}
