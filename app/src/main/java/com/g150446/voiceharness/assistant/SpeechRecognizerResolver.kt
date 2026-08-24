package com.g150446.voiceharness.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognitionService

/** Finds a real recognizer while excluding this app's required proxy service. */
internal object SpeechRecognizerResolver {
    fun resolveExternal(context: Context): ComponentName? {
        val intent = Intent(RecognitionService.SERVICE_INTERFACE)
        val services = if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.packageManager.queryIntentServices(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentServices(intent, PackageManager.MATCH_ALL)
        }
        return services.asSequence()
            .mapNotNull { it.serviceInfo }
            .filter { it.packageName != context.packageName }
            .map { ComponentName(it.packageName, it.name) }
            .firstOrNull()
    }
}
