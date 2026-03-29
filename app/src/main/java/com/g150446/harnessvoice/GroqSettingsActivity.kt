package com.g150446.harnessvoice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.g150446.harnessvoice.ui.theme.HarnessVoiceTheme

class GroqSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("groq_prefs", MODE_PRIVATE)
        val existing = prefs.getString("groq_api_key", "") ?: ""

        setContent {
            HarnessVoiceTheme {
                Scaffold { padding ->
                    var apiKey by remember { mutableStateOf(existing) }
                    var status by remember { mutableStateOf("") }

                    Column(modifier = Modifier.padding(padding).padding(16.dp)) {
                        Text("Groq API Settings")
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = { Text("GROQ_API_KEY") },
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = PasswordVisualTransformation()
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                prefs.edit {
                                    putString("groq_api_key", apiKey)
                                }
                                status = "Saved"
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Save")
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
