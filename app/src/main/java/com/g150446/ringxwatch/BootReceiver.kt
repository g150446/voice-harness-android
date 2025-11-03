package com.g150446.ringxwatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    
    private val TAG = "BootReceiver"
    
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_REPLACED -> {
                Log.d(TAG, "Boot completed - starting RingListenerService")
                
                // Start the service automatically on boot
                val serviceIntent = Intent(context, RingListenerService::class.java)
                context.startForegroundService(serviceIntent)
                
                Log.d(TAG, "RingListenerService started on boot")
            }
        }
    }
}
