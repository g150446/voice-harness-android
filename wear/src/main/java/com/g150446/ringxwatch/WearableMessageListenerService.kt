package com.g150446.ringxwatch

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.PowerManager
import android.util.Log
import android.view.Display
import com.g150446.ringxwatch.presentation.MainActivity
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class WearableMessageListenerService : WearableListenerService() {

    private val TAG = "WearableMsgListener"
    

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)

        Log.d(TAG, "Message received: ${messageEvent.path} from ${messageEvent.sourceNodeId}")

        if (messageEvent.path == "/ring_tap") {
            Log.d(TAG, "Ring tap message received - processing...")
            // Only proceed if the screen is ON (either interactive or ambient)
            if (!isScreenOnOrAmbient()) {
                Log.d(TAG, "Screen is OFF - ignoring ring tap event")
                return
            }
            
            // Check if the app is already running
            if (isAppRunning()) {
                Log.d(TAG, "App is already running - toggling recording")
                sendToggleRecordingIntent()
            } else {
                Log.d(TAG, "App is not running - launching main activity")
                // Launch the app normally
                launchMainActivity()
            }
        } else {
            Log.d(TAG, "Unknown message path: ${messageEvent.path}")
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        super.onDataChanged(dataEvents)
        for (event: DataEvent in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED) {
                val item = event.dataItem
                if (item.uri.path == "/settings") {
                    try {
                        val dataMap = DataMapItem.fromDataItem(item).dataMap
                        val apiKey = dataMap.getString("groq_api_key")
                        if (!apiKey.isNullOrEmpty()) {
                            WearGroqPrefs.saveApiKey(this, apiKey)
                            Log.d(TAG, "Saved Groq API key from phone")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse settings", e)
                    }
                }
            }
        }
    }

    private fun isAppRunning(): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningProcesses = activityManager.runningAppProcesses
        
        if (runningProcesses != null) {
            val myProcess = runningProcesses.find { process ->
                process.processName == packageName
            }
            
            // Check if app process is running
            if (myProcess != null) {
                Log.d(TAG, "App process found: importance=${myProcess.importance}")
                return true
            }
        }
        
        return false
    }
    
    private fun isScreenOnOrAmbient(): Boolean {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        return when (display?.state) {
            Display.STATE_ON -> true               // Interactive/active
            Display.STATE_DOZE -> true             // Ambient
            Display.STATE_DOZE_SUSPEND -> true     // Ambient (suspended)
            else -> false                          // OFF or unknown
        }
    }
    

    private fun sendIncrementCounterIntent() {
        try {
            if (!isScreenOnOrAmbient()) {
                Log.d(TAG, "Screen is OFF - not sending increment intent")
                return
            }

            val incrementIntent = Intent(this, MainActivity::class.java).apply {
                action = "INCREMENT_COUNTER"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or 
                         Intent.FLAG_ACTIVITY_CLEAR_TOP or
                         Intent.FLAG_ACTIVITY_SINGLE_TOP or
                         Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT)
            }
            Log.d(TAG, "Launching increment intent (screen is ON/ambient)")
            startActivity(incrementIntent)
            Log.d(TAG, "Increment intent sent successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating increment intent", e)
        }
    }

    private fun launchMainActivity() {
        try {
            if (!isScreenOnOrAmbient()) {
                Log.d(TAG, "Screen is OFF - not launching MainActivity")
                return
            }

            // Launch the main activity only when the screen is already ON (interactive or ambient)
            val startIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or 
                         Intent.FLAG_ACTIVITY_CLEAR_TOP or
                         Intent.FLAG_ACTIVITY_SINGLE_TOP or
                         Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT or
                         Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
            startActivity(startIntent)
            Log.d(TAG, "MainActivity launched successfully (screen ON/ambient)")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating launch intent", e)
        }
    }

    private fun sendToggleRecordingIntent() {
        try {
            if (!isScreenOnOrAmbient()) {
                Log.d(TAG, "Screen is OFF - not toggling recording")
                return
            }
            val toggleIntent = Intent(this, MainActivity::class.java).apply {
                action = "TOGGLE_RECORDING"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or 
                         Intent.FLAG_ACTIVITY_CLEAR_TOP or
                         Intent.FLAG_ACTIVITY_SINGLE_TOP or
                         Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT)
            }
            startActivity(toggleIntent)
            Log.d(TAG, "Sent toggle recording intent")
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling recording", e)
        }
    }
}
