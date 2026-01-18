package com.g150446.harnessvoice

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import com.g150446.harnessvoice.ui.theme.HarnessVoiceTheme
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MainActivity : ComponentActivity() {

    private lateinit var messageClient: MessageClient
    private lateinit var nodeClient: NodeClient
    private var isServiceRunning = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        messageClient = Wearable.getMessageClient(this)
        nodeClient = Wearable.getNodeClient(this)

        setContent {
            HarnessVoiceTheme {
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
                text = "Harness Voice Phone App",
                fontSize = 20.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = "Launch watch app manually",
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 16.dp)
            )

                // Settings button
                OutlinedButton(
                    onClick = {
                        context.startActivity(Intent(context, GroqSettingsActivity::class.java))
                    },
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    Text("⚙️ Settings")
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
                text = "Debug Log",
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
                    text = "No debug messages...",
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