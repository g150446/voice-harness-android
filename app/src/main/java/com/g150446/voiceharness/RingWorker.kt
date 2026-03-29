package com.g150446.voiceharness

import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class RingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val TAG = "RingWorker"
    private var powerManager: PowerManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "RingWorker started")
            DebugMessageManager.addMessage("WorkManager started")

            powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager

            try {
                wakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "HarnessVoice:WorkManagerWakeLock"
                )
                wakeLock?.acquire(60000)
                Log.d(TAG, "Wake lock acquired")
            } catch (e: Exception) {
                Log.e(TAG, "Error creating wake lock", e)
            }

            try {
                while (true) {
                    Log.d(TAG, "Monitoring...")
                    kotlinx.coroutines.delay(30000)
                    DebugMessageManager.addMessage("WorkManager active")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error monitoring", e)
            } finally {
                if (wakeLock?.isHeld == true) wakeLock?.release()
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "RingWorker failed", e)
            DebugMessageManager.addMessage("WorkManager failed: ${e.message}")
            Result.retry()
        }
    }
}
