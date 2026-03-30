package com.g150446.voiceharness

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

private const val TAG = "ServiceWatchdog"
private const val INTERVAL_MS = 5 * 60 * 1000L // 5 minutes (≥9 min in deep Doze)

/**
 * Schedules a recurring alarm that wakes the CPU even in Doze mode and ensures
 * BleConnectionService is running. This is the primary guard against the OS killing
 * the service after extended screen-off periods.
 *
 * Each alarm fires WatchdogReceiver, which starts the service (no-op if already alive)
 * and reschedules the next alarm. Not canceled on service destroy — that is intentional:
 * if the service is killed, the next alarm will restart it.
 */
object ServiceWatchdog {

    fun schedule(context: Context) {
        val pi = pendingIntent(context)
        val am = context.getSystemService(AlarmManager::class.java)
        val triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } else {
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
            Log.d(TAG, "Watchdog alarm scheduled in ${INTERVAL_MS / 1000}s")
        } catch (e: SecurityException) {
            // No SCHEDULE_EXACT_ALARM permission — fall back to inexact but Doze-compatible alarm.
            if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
            Log.d(TAG, "Watchdog alarm scheduled (inexact fallback) in ${INTERVAL_MS / 1000}s")
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context))
        Log.d(TAG, "Watchdog alarm canceled")
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, WatchdogReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
}
