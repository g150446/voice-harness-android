package com.g150446.harnessvoice

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.Node
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await

class RingAccessibilityService : AccessibilityService() {
    private val TAG = "RingAccessibilityService"
    private val scope = CoroutineScope(Dispatchers.Main)
    
    private var messageClient: MessageClient? = null
    private var nodeClient: NodeClient? = null
    private var powerManager: PowerManager? = null
    private var audioManager: AudioManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenWakeLock: PowerManager.WakeLock? = null
    
    // Ring tap debouncing to prevent rapid taps
    private var lastRingTapTime: Long = 0
    private companion object {
        const val RING_TAP_COOLDOWN_MS = 1000L // 1 second cooldown between ring taps
        var isServiceRunning = false
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "RingAccessibilityService created")
        
        // Initialize Google Play Services
        messageClient = Wearable.getMessageClient(this)
        nodeClient = Wearable.getNodeClient(this)
        
        // Initialize system services
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
        // Setup wake locks for reliable operation
        setupWakeLocks()
        
        isServiceRunning = true
        DebugMessageManager.addMessage("🔧 Accessibility Service started - Ultimate Doze resistance!")
        
        // Start accessibility monitoring to keep service active
        startAccessibilityMonitoring()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "RingAccessibilityService destroyed")
        
        // Release wake locks
        releaseWakeLocks()
        
        isServiceRunning = false
        DebugMessageManager.addMessage("❌ Accessibility Service stopped")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility service connected")
        
        // Configure the service
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
            packageNames = arrayOf(packageName)
        }
        serviceInfo = info
        
        DebugMessageManager.addMessage("✅ Accessibility Service connected - System-level access granted!")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.let { accessibilityEvent ->
            // Provide actual accessibility functionality to prevent service termination
            when (accessibilityEvent.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    // Log window changes for accessibility purposes
                    Log.d(TAG, "Window state changed: ${accessibilityEvent.packageName}")
                }
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    // Log view clicks for accessibility purposes
                    Log.d(TAG, "View clicked: ${accessibilityEvent.packageName}")
                }
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                    // Log notification changes for accessibility purposes
                    Log.d(TAG, "Notification state changed: ${accessibilityEvent.packageName}")
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
        DebugMessageManager.addMessage("⚠ Accessibility Service interrupted")
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        event?.let { keyEvent ->
            Log.d(TAG, "Key event received: ${keyEvent.keyCode}, action: ${keyEvent.action}")
            
            // Check if it's a media button event (ring tap)
            if (isMediaButtonEvent(keyEvent)) {
                // Check cooldown to prevent rapid taps
                val currentTime = System.currentTimeMillis()
                val timeSinceLastTap = currentTime - lastRingTapTime
                
                if (timeSinceLastTap < RING_TAP_COOLDOWN_MS) {
                    Log.d(TAG, "Ring tap ignored - cooldown active (${timeSinceLastTap}ms < ${RING_TAP_COOLDOWN_MS}ms)")
                    DebugMessageManager.addMessage("⏱️ Ring tap ignored - cooldown active")
                    return true // Consume the event even if we ignore it
                }
                
                // Cooldown passed - legitimate ring tap
                lastRingTapTime = currentTime
                Log.d(TAG, "Ring tap accepted - time since last tap: ${timeSinceLastTap}ms")
                Log.d(TAG, "Media button event detected - ring tap!")
                DebugMessageManager.addMessage("💍 Ring tap detected via Accessibility Service!")
                
                // Handle ring tap
                handleRingTap()
                return true // Consume the event
            }
        }
        
        return super.onKeyEvent(event)
    }

    private fun isMediaButtonEvent(keyEvent: KeyEvent): Boolean {
        return when (keyEvent.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_HEADSETHOOK -> true
            else -> false
        }
    }

    private fun handleRingTap() {
        scope.launch {
            try {
                // Acquire wake locks for reliable operation
                acquireWakeLocks()
                
                // Send message to watch
                sendMessageToWatch()
                
                // Keep wake locks for a short time to ensure message is sent
                kotlinx.coroutines.delay(3000)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error handling ring tap", e)
                DebugMessageManager.addMessage("❌ Error handling ring tap: ${e.message}")
            } finally {
                // Release wake locks
                releaseWakeLocks()
            }
        }
    }

    private fun sendMessageToWatch() {
        scope.launch {
            try {
                val nodes = nodeClient?.connectedNodes?.await()
                Log.d(TAG, "Found ${nodes?.size ?: 0} connected nodes")
                
                if (nodes.isNullOrEmpty()) {
                    DebugMessageManager.addMessage("⚠ No watch connected")
                    return@launch
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

    private fun setupWakeLocks() {
        try {
            // Create wake locks for reliable operation
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "HarnessVoice:AccessibilityWakeLock"
            )
            
            screenWakeLock = powerManager?.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "HarnessVoice:AccessibilityScreenWakeLock"
            )
            
            Log.d(TAG, "Wake locks created")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating wake locks", e)
        }
    }

    private fun acquireWakeLocks() {
        try {
            wakeLock?.acquire(5000) // 5 seconds
            screenWakeLock?.acquire(3000) // 3 seconds
            Log.d(TAG, "Wake locks acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring wake locks", e)
        }
    }

    private fun releaseWakeLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            if (screenWakeLock?.isHeld == true) {
                screenWakeLock?.release()
            }
            Log.d(TAG, "Wake locks released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wake locks", e)
        }
    }

    private fun startAccessibilityMonitoring() {
        scope.launch {
            while (isServiceRunning) {
                try {
                    // Provide accessibility monitoring to keep service active
                    Log.d(TAG, "Accessibility service monitoring active")
                    
                    // Wait 60 seconds before next check
                    kotlinx.coroutines.delay(60000)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in accessibility monitoring", e)
                    kotlinx.coroutines.delay(60000)
                }
            }
        }
    }
}
