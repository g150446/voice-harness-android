package com.g150446.harnessvoice

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RingAccessibilityService : AccessibilityService() {
    private val TAG = "RingAccessibilityService"
    private val scope = CoroutineScope(Dispatchers.Main)

    private var powerManager: PowerManager? = null
    private var audioManager: AudioManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenWakeLock: PowerManager.WakeLock? = null

    private var lastRingTapTime: Long = 0
    private companion object {
        const val RING_TAP_COOLDOWN_MS = 1000L
        var isServiceRunning = false
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "RingAccessibilityService created")

        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        setupWakeLocks()

        isServiceRunning = true
        DebugMessageManager.addMessage("Accessibility Service started")

        startAccessibilityMonitoring()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "RingAccessibilityService destroyed")
        releaseWakeLocks()
        isServiceRunning = false
        DebugMessageManager.addMessage("Accessibility Service stopped")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility service connected")

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

        DebugMessageManager.addMessage("Accessibility Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.let { accessibilityEvent ->
            when (accessibilityEvent.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                    Log.d(TAG, "Window state changed: ${accessibilityEvent.packageName}")
                AccessibilityEvent.TYPE_VIEW_CLICKED ->
                    Log.d(TAG, "View clicked: ${accessibilityEvent.packageName}")
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED ->
                    Log.d(TAG, "Notification state changed: ${accessibilityEvent.packageName}")
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
        DebugMessageManager.addMessage("Accessibility Service interrupted")
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        event?.let { keyEvent ->
            Log.d(TAG, "Key event received: ${keyEvent.keyCode}, action: ${keyEvent.action}")

            if (isMediaButtonEvent(keyEvent)) {
                val currentTime = System.currentTimeMillis()
                val timeSinceLastTap = currentTime - lastRingTapTime

                if (timeSinceLastTap < RING_TAP_COOLDOWN_MS) {
                    Log.d(TAG, "Ring tap ignored - cooldown active")
                    return true
                }

                lastRingTapTime = currentTime
                Log.d(TAG, "Media button event detected - ring tap!")
                DebugMessageManager.addMessage("Ring tap detected via Accessibility Service")

                handleRingTap()
                return true
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
                acquireWakeLocks()
                Log.d(TAG, "Ring tap handled")
                DebugMessageManager.addMessage("Ring tap handled")
                kotlinx.coroutines.delay(3000)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling ring tap", e)
                DebugMessageManager.addMessage("Error handling ring tap: ${e.message}")
            } finally {
                releaseWakeLocks()
            }
        }
    }

    private fun setupWakeLocks() {
        try {
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
            wakeLock?.acquire(5000)
            screenWakeLock?.acquire(3000)
            Log.d(TAG, "Wake locks acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring wake locks", e)
        }
    }

    private fun releaseWakeLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (screenWakeLock?.isHeld == true) screenWakeLock?.release()
            Log.d(TAG, "Wake locks released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wake locks", e)
        }
    }

    private fun startAccessibilityMonitoring() {
        scope.launch {
            while (isServiceRunning) {
                try {
                    Log.d(TAG, "Accessibility service monitoring active")
                    kotlinx.coroutines.delay(60000)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in accessibility monitoring", e)
                    kotlinx.coroutines.delay(60000)
                }
            }
        }
    }
}
