package com.g150446.voiceharness

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

private const val TAG = "WatchdogReceiver"

/**
 * Fired by ServiceWatchdog every ~5 minutes (≥9 min in deep Doze).
 * Ensures BleConnectionService is alive and reschedules the next alarm.
 */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Watchdog fired — ensuring BleConnectionService is alive")
        BleConnectionService.start(context)
        ServiceWatchdog.schedule(context)
    }
}
