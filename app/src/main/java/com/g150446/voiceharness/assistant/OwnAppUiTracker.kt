package com.g150446.voiceharness.assistant

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Tracks whether any of this app's activities is resumed (UI visible). */
object OwnAppUiTracker {
    private val resumedCount = AtomicInteger(0)
    private val registered = AtomicBoolean(false)

    fun register(application: Application) {
        if (!registered.compareAndSet(false, true)) return
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    resumedCount.incrementAndGet()
                }

                override fun onActivityPaused(activity: Activity) {
                    resumedCount.updateAndGet { (it - 1).coerceAtLeast(0) }
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }

    fun isOwnUiShowing(): Boolean = resumedCount.get() > 0
}
