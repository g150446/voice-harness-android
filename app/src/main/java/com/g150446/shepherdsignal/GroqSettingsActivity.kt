package com.g150446.shepherdsignal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.g150446.shepherdsignal.ui.theme.ShepherdSignalTheme
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class GroqSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("groq_prefs", MODE_PRIVATE)
        val existing = prefs.getString("groq_api_key", "") ?: ""

        setContent {
            ShepherdSignalTheme {
                Scaffold { padding ->
                    var apiKey by remember { mutableStateOf(existing) }
                    var status by remember { mutableStateOf("") }
                    val dataClient: DataClient = Wearable.getDataClient(this)

                    Column(modifier = Modifier.padding(padding).padding(16.dp)) {
                        Text("Groq API Settings")
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = { Text("GROQ_API_KEY") },
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = PasswordVisualTransformation()
                        )
                        Button(
                            onClick = {
                                prefs.edit { putString("groq_api_key", apiKey) }
                                // sync to wear via Data Layer
                                status = "Saving..."
                                CoroutineScope(Dispatchers.IO).launch {
                                    try {
                                        val putReq = PutDataMapRequest.create("/settings").apply {
                                            dataMap.putString("groq_api_key", apiKey)
                                            dataMap.putLong("updated_at", System.currentTimeMillis())
                                        }.asPutDataRequest().setUrgent()
                                        dataClient.putDataItem(putReq).await()
                                        launch(Dispatchers.Main) { status = "Saved and synced" }
                                    } catch (e: Exception) {
                                        launch(Dispatchers.Main) { status = "Error: ${e.message}" }
                                    }
                                }
                            },
                            modifier = Modifier.padding(top = 12.dp)
                        ) {
                            Text("Save & Sync to Watch")
                        }
                        if (status.isNotEmpty()) {
                            Text(status, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
        }
    }
}


