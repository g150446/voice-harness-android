package com.g150446.voiceharness

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.Locale

private const val TAG = "ReminderAlarmReceiver"
private const val CHANNEL_ID = "reminder_channel"
private const val CHANNEL_NAME = "Reminders"
private const val NOTIFICATION_BASE_ID = 2000

class ReminderAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_REMINDER_TITLE = "reminder_title"
        const val EXTRA_REMINDER_TTS = "reminder_tts_enabled"
        const val ACTION_COMPLETE = "com.g150446.voiceharness.action.COMPLETE_REMINDER"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getStringExtra(EXTRA_REMINDER_ID) ?: return
        val title = intent.getStringExtra(EXTRA_REMINDER_TITLE) ?: "リマインダー"

        Log.d(TAG, "Alarm fired for reminder: $title ($reminderId)")

        createNotificationChannel(context)

        val contentIntent = PendingIntent.getActivity(
            context,
            reminderId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_REMINDER_ID, reminderId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val completeIntent = PendingIntent.getBroadcast(
            context,
            reminderId.hashCode() + 1,
            Intent(context, ReminderCompleteReceiver::class.java).apply {
                putExtra(EXTRA_REMINDER_ID, reminderId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("リマインダー")
            .setContentText(title)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "完了",
                completeIntent
            )
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_BASE_ID + reminderId.hashCode(), notification)

        val ttsEnabled = intent.getBooleanExtra(EXTRA_REMINDER_TTS, false)
        if (ttsEnabled) {
            speakReminderAloud(context, title)
        }

        // Mark as completed in repository so it won't show as pending
        ReminderRepository(context).markCompleted(reminderId)
    }

    private fun speakReminderAloud(context: Context, title: String) {
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context) { status ->
            val ttsInstance = tts
            if (status == TextToSpeech.SUCCESS && ttsInstance != null) {
                val result = ttsInstance.setLanguage(Locale.JAPANESE)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    ttsInstance.setLanguage(Locale.getDefault())
                }
                val utteranceId = "reminder_${System.currentTimeMillis()}"
                ttsInstance.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        ttsInstance.shutdown()
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        ttsInstance.shutdown()
                    }
                })
                ttsInstance.speak(
                    "${title}の時間です",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    utteranceId
                )
                Log.d(TAG, "Speaking reminder aloud: $title")
            } else {
                Log.w(TAG, "TTS initialization failed, cannot speak reminder aloud")
                ttsInstance?.shutdown()
            }
        }
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Harness Voice reminder notifications"
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }
}

class ReminderCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getStringExtra(ReminderAlarmReceiver.EXTRA_REMINDER_ID) ?: return
        ReminderRepository(context).markCompleted(reminderId)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_BASE_ID + reminderId.hashCode())
        Log.d(TAG, "Marked reminder as completed: $reminderId")
    }
}
