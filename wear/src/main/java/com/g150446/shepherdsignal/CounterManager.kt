package com.g150446.shepherdsignal

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Calendar
import java.util.concurrent.TimeUnit

class CounterManager(private val context: Context) {
    
    companion object {
        private const val TAG = "CounterManager"
        private const val PREFS_NAME = "RingTapCounter"
        private const val KEY_COUNT = "tap_count"
        private const val KEY_LAST_RESET = "last_reset_time"
    }
    
    private val preferences: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private var resetRunnable: Runnable? = null
    
    private var currentCount: Int
    private var lastResetTime: Long
    
    init {
        currentCount = preferences.getInt(KEY_COUNT, 0)
        lastResetTime = preferences.getLong(KEY_LAST_RESET, System.currentTimeMillis())
        
        // Check if we need to reset based on the saved time
        checkAndResetIfNeeded()
        
        // Schedule the next reset
        scheduleNextReset()
        
        Log.d(TAG, "CounterManager initialized - Current count: $currentCount")
    }
    
    fun incrementCount(): Int {
        currentCount++
        saveCount()
        Log.d(TAG, "Counter incremented to: $currentCount")
        return currentCount
    }
    
    fun getCurrentCount(): Int {
        return currentCount
    }
    
    fun resetCount() {
        currentCount = 0
        lastResetTime = System.currentTimeMillis()
        saveCount()
        scheduleNextReset()
        Log.d(TAG, "Counter reset to 0")
    }
    
    private fun checkAndResetIfNeeded() {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastReset = currentTime - lastResetTime
        val oneHourInMillis = TimeUnit.HOURS.toMillis(1)
        
        if (timeSinceLastReset >= oneHourInMillis) {
            resetCount()
        }
    }
    
    private fun scheduleNextReset() {
        // Cancel any existing reset runnable
        resetRunnable?.let { handler.removeCallbacks(it) }
        
        val currentTime = System.currentTimeMillis()
        val nextResetTime = getNextResetTime()
        val delayMillis = nextResetTime - currentTime
        
        resetRunnable = Runnable {
            resetCount()
        }
        
        handler.postDelayed(resetRunnable!!, delayMillis)
        
        val nextResetTimeFormatted = Calendar.getInstance().apply {
            timeInMillis = nextResetTime
        }
        
        Log.d(TAG, "Next reset scheduled at: $nextResetTimeFormatted (${delayMillis / 1000} seconds from now)")
    }
    
    private fun getNextResetTime(): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = lastResetTime
        
        // Add one hour to the last reset time
        calendar.add(Calendar.HOUR_OF_DAY, 1)
        
        return calendar.timeInMillis
    }
    
    private fun saveCount() {
        preferences.edit()
            .putInt(KEY_COUNT, currentCount)
            .putLong(KEY_LAST_RESET, lastResetTime)
            .apply()
    }
    
    fun cleanup() {
        resetRunnable?.let { handler.removeCallbacks(it) }
    }
}
