package com.g150446.voiceharness

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

private const val TAG = "ReminderAlarmScheduler"

/**
 * Schedules exact alarms for reminder notifications.
 * Uses AlarmManager.setExactAndAllowWhileIdle with fallback.
 */
object ReminderAlarmScheduler {

    fun schedule(context: Context, entry: ReminderEntry) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context, entry.id, entry.title, entry.isTtsEnabled)

        try {
            if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    entry.scheduledAtMillis,
                    pi
                )
            } else {
                am.setExact(
                    AlarmManager.RTC_WAKEUP,
                    entry.scheduledAtMillis,
                    pi
                )
            }
            Log.d(TAG, "Scheduled reminder alarm for ${entry.title} at ${entry.scheduledAtMillis}")
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm permission not available, falling back to inexact alarm", e)
            if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    entry.scheduledAtMillis,
                    pi
                )
            } else {
                am.set(
                    AlarmManager.RTC_WAKEUP,
                    entry.scheduledAtMillis,
                    pi
                )
            }
        }
    }

    fun cancel(context: Context, reminderId: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context, reminderId, "", false)
        am.cancel(pi)
        pi.cancel()
        Log.d(TAG, "Canceled reminder alarm: $reminderId")
    }

    private fun pendingIntent(context: Context, reminderId: String, title: String, isTtsEnabled: Boolean): PendingIntent {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            putExtra(ReminderAlarmReceiver.EXTRA_REMINDER_ID, reminderId)
            putExtra(ReminderAlarmReceiver.EXTRA_REMINDER_TITLE, title)
            putExtra(ReminderAlarmReceiver.EXTRA_REMINDER_TTS, isTtsEnabled)
        }
        val requestCode = reminderId.hashCode()
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
