package com.g150446.voiceharness

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Floating on-screen recording indicator over other apps.
 * Requires [Settings.canDrawOverlays]. Does not intercept touches.
 */
class RecordingOverlayController(context: Context) {
    private val appContext = context.applicationContext
    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var rootView: View? = null
    private var pulseRunnable: Runnable? = null
    private var visible = false

    fun show() {
        mainHandler.post {
            if (visible) return@post
            if (!canDrawOverlays()) {
                Log.d(TAG, "Overlay skipped: no SYSTEM_ALERT_WINDOW permission")
                return@post
            }
            runCatching { attach() }
                .onFailure { Log.e(TAG, "Failed to show recording overlay", it) }
        }
    }

    fun hide() {
        mainHandler.post {
            runCatching { detach() }
                .onFailure { Log.w(TAG, "Failed to hide recording overlay", it) }
        }
    }

    fun canDrawOverlays(): Boolean =
        Settings.canDrawOverlays(appContext)

    private fun attach() {
        if (rootView != null) {
            visible = true
            return
        }
        val density = appContext.resources.displayMetrics.density
        val sizePx = (56f * density).toInt()
        val topMarginPx = (28f * density).toInt()
        val iconSizePx = (28f * density).toInt()

        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xE6E53935.toInt())
        }

        val icon = ImageView(appContext).apply {
            setImageResource(R.drawable.ic_recording_mic)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        val container = FrameLayout(appContext).apply {
            background = circle
            addView(
                icon,
                FrameLayout.LayoutParams(iconSizePx, iconSizePx, Gravity.CENTER),
            )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "録音中"
            alpha = 0.92f
        }

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = topMarginPx
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            title = "Harness recording"
        }

        windowManager.addView(container, params)
        rootView = container
        visible = true
        startPulse(container)
        Log.i(TAG, "Recording overlay shown")
    }

    private fun detach() {
        stopPulse()
        val view = rootView ?: run {
            visible = false
            return
        }
        rootView = null
        visible = false
        windowManager.removeView(view)
        Log.i(TAG, "Recording overlay hidden")
    }

    private fun startPulse(view: View) {
        stopPulse()
        var growing = false
        val runnable = object : Runnable {
            override fun run() {
                if (rootView !== view) return
                growing = !growing
                view.animate()
                    .scaleX(if (growing) 1.12f else 1f)
                    .scaleY(if (growing) 1.12f else 1f)
                    .setDuration(450L)
                    .start()
                mainHandler.postDelayed(this, 500L)
            }
        }
        pulseRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun stopPulse() {
        pulseRunnable?.let { mainHandler.removeCallbacks(it) }
        pulseRunnable = null
        rootView?.animate()?.cancel()
        rootView?.scaleX = 1f
        rootView?.scaleY = 1f
    }

    companion object {
        private const val TAG = "RecordingOverlay"

        fun openPermissionSettings(context: Context) {
            val intent = android.content.Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}"),
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
