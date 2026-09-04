package com.g150446.voiceharness

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionEvent
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.TimeUnit

enum class DrivingMode { NORMAL, DRIVING }

/** Activity Recognition based vehicle detection with an explicit manual override. */
class DrivingModeController(private val context: Context) {
    companion object {
        private const val TAG = "DrivingMode"
        private const val PREFS = "driving_mode"
        private const val KEY_OVERRIDE = "override" // -1 auto, 0 normal, 1 driving
        const val ACTION_ACTIVITY = "com.g150446.voiceharness.action.ACTIVITY_TRANSITION"
        const val ACTION_SET_MODE = "com.g150446.voiceharness.action.SET_DRIVING_MODE"
        const val EXTRA_MODE = "mode"
        private const val PI_REQUEST = 4817
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _mode = MutableStateFlow(currentMode())
    val mode: StateFlow<DrivingMode> = _mode

    fun start() {
        if (override() != -1 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) !=
            PackageManager.PERMISSION_GRANTED) return
        val transitions = listOf(
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                .build(),
        )
        ActivityRecognition.getClient(context)
            .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pendingIntent())
            .addOnFailureListener { Log.w(TAG, "Activity Recognition unavailable", it) }
    }

    fun stop() {
        runCatching { ActivityRecognition.getClient(context).removeActivityTransitionUpdates(pendingIntent()) }
    }

    fun setOverride(mode: DrivingMode?) {
        prefs.edit().putInt(KEY_OVERRIDE, when (mode) { null -> -1; DrivingMode.NORMAL -> 0; DrivingMode.DRIVING -> 1 }).apply()
        _mode.value = mode ?: DrivingMode.NORMAL
        BleConnectionService.setDrivingMode(context, _mode.value)
        if (mode == null) start()
    }

    fun onTransition(event: ActivityTransitionEvent) {
        if (override() != -1 || event.activityType != DetectedActivity.IN_VEHICLE) return
        val next = if (event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) DrivingMode.DRIVING else DrivingMode.NORMAL
        _mode.value = next
        BleConnectionService.setDrivingMode(context, next)
    }

    /** Default 0 = force NORMAL (no Activity Recognition). Auto is opt-in via setOverride(null). */
    private fun override() = prefs.getInt(KEY_OVERRIDE, 0)
    private fun currentMode() = when (override()) { 1 -> DrivingMode.DRIVING; else -> DrivingMode.NORMAL }
    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, PI_REQUEST, Intent(context, DrivingActivityReceiver::class.java).setAction(ACTION_ACTIVITY),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

class DrivingActivityReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ActivityTransitionResult.extractResult(intent)?.transitionEvents?.forEach {
            DrivingModeController(context.applicationContext).onTransition(it)
        }
    }
}
