package com.g150446.harnessvoice

import android.content.Context
import android.media.AudioManager
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.Node
import kotlinx.coroutines.tasks.await

class RingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val TAG = "RingWorker"
    private var messageClient: MessageClient? = null
    private var nodeClient: NodeClient? = null
    private var powerManager: PowerManager? = null
    private var audioManager: AudioManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "RingWorker started")
            DebugMessageManager.addMessage("🔄 WorkManager started - Doze-resistant background task")
            
            // Initialize services
            initializeServices()
            
            // Setup wake locks
            setupWakeLocks()
            
            // Keep the worker running and monitor for ring gestures
            monitorRingGestures()
            
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "RingWorker failed", e)
            DebugMessageManager.addMessage("❌ WorkManager failed: ${e.message}")
            Result.retry()
        }
    }

    private fun initializeServices() {
        messageClient = Wearable.getMessageClient(applicationContext)
        nodeClient = Wearable.getNodeClient(applicationContext)
        powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        audioManager = applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private fun setupWakeLocks() {
        try {
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "HarnessVoice:WorkManagerWakeLock"
            )
            wakeLock?.acquire(60000) // 1 minute
            Log.d(TAG, "Wake lock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating wake lock", e)
        }
    }

    private suspend fun monitorRingGestures() {
        try {
            // WorkManager is designed to handle Doze mode
            // Keep the worker active by periodically checking for ring gestures
            while (true) {
                // Simulate ring gesture monitoring
                // In a real implementation, this would monitor for actual ring gestures
                Log.d(TAG, "Monitoring for ring gestures...")
                
                // Wait 30 seconds before next check
                kotlinx.coroutines.delay(30000)
                
                // Provide feedback that worker is active
                DebugMessageManager.addMessage("🔄 WorkManager active - monitoring ring gestures")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error monitoring ring gestures", e)
        } finally {
            // Release wake lock
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
    }

    private suspend fun sendMessageToWatch() {
        try {
            val nodes = nodeClient?.connectedNodes?.await()
            Log.d(TAG, "Found ${nodes?.size ?: 0} connected nodes")
            
            if (nodes.isNullOrEmpty()) {
                DebugMessageManager.addMessage("⚠ No watch connected")
                return
            }
            
            // Send message to all connected nodes
            nodes.forEach { node: Node ->
                val message = "ring_tap"
                messageClient?.sendMessage(node.id, "/ring_tap", message.toByteArray())
                Log.d(TAG, "Sent message to node: ${node.id}")
            }
            
            DebugMessageManager.addMessage("📱 Message sent to ${nodes.size} watch(es)")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message to watch", e)
            DebugMessageManager.addMessage("❌ Error sending message: ${e.message}")
        }
    }
}
