package com.g150446.ringxwatch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.util.Log
import com.g150446.ringxwatch.ui.theme.RingXwatchTheme
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MainActivity : ComponentActivity() {

    private lateinit var messageClient: MessageClient
    private lateinit var nodeClient: NodeClient
    private var isServiceRunning = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            requestAudioPermissionAndStartService()
        } else {
            Log.w("MainActivity", "Notification permission denied")
        }
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startRingListenerService()
        } else {
            Log.w("MainActivity", "Audio permission denied, starting service anyway")
            startRingListenerService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        messageClient = Wearable.getMessageClient(this)
        nodeClient = Wearable.getNodeClient(this)

        // Request notification permission and start service
        requestNotificationPermissionAndStartService()

        setContent {
            RingXwatchTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    LaunchWearAppScreen(
                        modifier = Modifier.padding(innerPadding),
                        messageClient = messageClient,
                        nodeClient = nodeClient
                    )
                }
            }
        }
    }

    private fun requestNotificationPermissionAndStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    requestAudioPermissionAndStartService()
                }
                else -> {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            requestAudioPermissionAndStartService()
        }
    }

    private fun requestAudioPermissionAndStartService() {
        val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        when {
            ContextCompat.checkSelfPermission(this, audioPermission) == PackageManager.PERMISSION_GRANTED -> {
                startRingListenerService()
            }
            else -> {
                audioPermissionLauncher.launch(audioPermission)
            }
        }
    }

    private fun startRingListenerService() {
        // Use Auxio-style service instead of complex multi-service approach
        val intent = Intent(this, AuxioStyleService::class.java)
        ContextCompat.startForegroundService(this, intent)
        isServiceRunning = true
        Log.d("MainActivity", "Auxio-style service started")
        
        // Check and request battery optimization exemption
        checkBatteryOptimization()
    }
    
    private fun checkBatteryOptimization() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = getSystemService(POWER_SERVICE) as PowerManager
                if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                    Log.d("MainActivity", "App is not exempt from battery optimization")
                    DebugMessageManager.addMessage("🔋 Battery optimization detected")
                    DebugMessageManager.addMessage("⚠ Ring gestures may not work reliably in sleep mode")
                    DebugMessageManager.addMessage("💡 Tap 'Disable Battery Optimization' button below")
                } else {
                    Log.d("MainActivity", "App is exempt from battery optimization")
                    DebugMessageManager.addMessage("✅ Battery optimization disabled - ring gestures will work reliably")
                }
            } else {
                DebugMessageManager.addMessage("✅ Battery optimization not applicable on this Android version")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error checking battery optimization", e)
            DebugMessageManager.addMessage("❌ Error checking battery optimization status")
        }
    }

    private fun openBatteryOptimizationSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
                Log.d("MainActivity", "Opened battery optimization settings")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to open battery optimization settings", e)
            // Fallback to general battery settings
            try {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(intent)
                Log.d("MainActivity", "Opened general battery optimization settings")
            } catch (e2: Exception) {
                Log.e("MainActivity", "Failed to open general battery settings", e2)
            }
        }
    }


    private fun stopRingListenerService() {
        val intent = Intent(this, RingListenerService::class.java)
        stopService(intent)
        isServiceRunning = false
        Log.d("MainActivity", "Ring listener service stopped")
    }

    override fun onDestroy() {
        super.onDestroy()
        // Don't stop the service - let it run in background
        // stopRingListenerService()
    }
}

@Composable
fun LaunchWearAppScreen(
    modifier: Modifier = Modifier,
    messageClient: MessageClient,
    nodeClient: NodeClient
) {
    var isLaunching by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val debugMessages by DebugMessageManager.messages.collectAsState()
    val playbackState by PlaybackStateManager.state.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Main content area
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "RingXwatch Phone App",
                fontSize = 20.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = if (playbackState.isPlaying) "🎵 Playing: ${playbackState.currentTrack}" else "⏸ Paused: ${playbackState.currentTrack}",
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = "Tap your ring to launch watch app",
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Play/Pause button
            Button(
                onClick = {
                    val intent = Intent(context, AuxioStyleService::class.java).apply {
                        action = "TOGGLE_PLAYBACK"
                    }
                    context.startService(intent)
                },
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Text(if (playbackState.isPlaying) "⏸ Pause Music" else "▶ Play Music")
            }

            // Battery Optimization button
            Button(
                onClick = {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                },
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Text("🔋 Disable Battery Optimization")
            }

                // Groq Settings button
                OutlinedButton(
                    onClick = {
                        context.startActivity(Intent(context, GroqSettingsActivity::class.java))
                    },
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    Text("🔑 Groq API Settings")
                }

            if (isLaunching) {
                CircularProgressIndicator()
                Text(
                    text = statusMessage,
                    modifier = Modifier.padding(top = 16.dp)
                )
            } else {
                Button(
                    onClick = {
                        scope.launch {
                            isLaunching = true
                            statusMessage = "Connecting to watch..."
                            try {
                                val nodes = nodeClient.connectedNodes.await()
                                Log.d("PhoneApp", "Found ${nodes.size} connected nodes")

                                if (nodes.isEmpty()) {
                                    statusMessage = "No watch connected"
                                    Log.w("PhoneApp", "No connected nodes found")
                                } else {
                                    statusMessage = "Sending launch command..."
                                    nodes.forEach { node ->
                                        Log.d("PhoneApp", "Sending message to node: ${node.displayName} (${node.id})")
                                        messageClient.sendMessage(
                                            node.id,
                                            "/launch_app",
                                            ByteArray(0)
                                        ).await()
                                        Log.d("PhoneApp", "Message sent successfully")
                                    }
                                    statusMessage = "Launch command sent!"
                                }
                            } catch (e: Exception) {
                                Log.e("PhoneApp", "Error sending message", e)
                                statusMessage = "Error: ${e.message}"
                                e.printStackTrace()
                            }
                            kotlinx.coroutines.delay(2000)
                            isLaunching = false
                            statusMessage = ""
                        }
                    }
                ) {
                    Text("Launch Watch App")
                }

                if (statusMessage.isNotEmpty()) {
                    Text(
                        text = statusMessage,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            }
        }

        // Debug log section
        HorizontalDivider()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .weight(0.6f)
                .padding(8.dp)
        ) {
            Text(
                text = "Debug Log (Ring Tap Detection)",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            TextButton(
                onClick = { DebugMessageManager.clear() },
                modifier = Modifier.padding(bottom = 4.dp)
            ) {
                Text("Clear Log", fontSize = 10.sp)
            }

            if (debugMessages.isEmpty()) {
                Text(
                    text = "Waiting for ring tap events...",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LazyColumn {
                    items(debugMessages) { message ->
                        Text(
                            text = message,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}