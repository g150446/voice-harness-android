package com.g150446.voiceharness

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
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
import android.widget.TextView

internal sealed interface HarnessOverlayStatus {
    data object Recording : HarnessOverlayStatus
    data class ReadingPassthrough(
        val page: Int,
        val pageCount: Int,
        val loading: Boolean = false,
    ) : HarnessOverlayStatus
}

internal fun overlayStatusFor(
    voiceState: VoiceState,
    glassesState: SmartGlassesState,
): HarnessOverlayStatus? = when {
    voiceState == VoiceState.RECORDING -> HarnessOverlayStatus.Recording
    glassesState.readingPassthroughActive && glassesState.readingPageCount > 0 ->
        HarnessOverlayStatus.ReadingPassthrough(
            page = glassesState.readingPage.coerceIn(1, glassesState.readingPageCount),
            pageCount = glassesState.readingPageCount,
            loading = glassesState.readingPageLoading,
        )
    else -> null
}

internal fun readingPassthroughOverlayLabel(page: Int, pageCount: Int): String =
    "リーダー $page/$pageCount"

/**
 * Floating recording / reader-mode indicator over other apps.
 * Requires [Settings.canDrawOverlays]. Does not intercept touches.
 */
class RecordingOverlayController(context: Context) {
    private val appContext = context.applicationContext
    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var rootView: View? = null
    private var pulseRunnable: Runnable? = null
    private var visibleStatus: HarnessOverlayStatus? = null

    /** Retained compatibility entry point for the existing recording state. */
    fun show() {
        show(HarnessOverlayStatus.Recording)
    }

    internal fun show(status: HarnessOverlayStatus?) {
        mainHandler.post {
            if (status == null) {
                runCatching { detach() }
                    .onFailure { Log.w(TAG, "Failed to hide status overlay", it) }
                return@post
            }
            if (visibleStatus == status && rootView != null) return@post
            if (!canDrawOverlays()) {
                Log.d(TAG, "Overlay skipped: no SYSTEM_ALERT_WINDOW permission")
                return@post
            }
            runCatching {
                detach()
                attach(status)
            }.onFailure { Log.e(TAG, "Failed to show status overlay", it) }
        }
    }

    fun hide() {
        show(null)
    }

    fun canDrawOverlays(): Boolean =
        Settings.canDrawOverlays(appContext)

    private fun attach(status: HarnessOverlayStatus) {
        val density = appContext.resources.displayMetrics.density
        val topMarginPx = (28f * density).toInt()
        val view = when (status) {
            HarnessOverlayStatus.Recording -> createRecordingView(density)
            is HarnessOverlayStatus.ReadingPassthrough -> createReadingView(status, density)
        }
        val (width, height) = when (status) {
            HarnessOverlayStatus.Recording -> (56f * density).toInt() to (56f * density).toInt()
            is HarnessOverlayStatus.ReadingPassthrough ->
                WindowManager.LayoutParams.WRAP_CONTENT to (40f * density).toInt()
        }

        val params = WindowManager.LayoutParams(
            width,
            height,
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
            title = when (status) {
                HarnessOverlayStatus.Recording -> "Harness recording"
                is HarnessOverlayStatus.ReadingPassthrough -> "Harness reader mode"
            }
        }

        windowManager.addView(view, params)
        rootView = view
        visibleStatus = status
        if (status == HarnessOverlayStatus.Recording) startPulse(view)
        Log.i(TAG, "Status overlay shown: $status")
    }

    private fun createRecordingView(density: Float): View {
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
        return FrameLayout(appContext).apply {
            background = circle
            addView(
                icon,
                FrameLayout.LayoutParams(iconSizePx, iconSizePx, Gravity.CENTER),
            )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "録音中"
            alpha = 0.92f
        }
    }

    private fun createReadingView(
        status: HarnessOverlayStatus.ReadingPassthrough,
        density: Float,
    ): View {
        val horizontalPadding = (14f * density).toInt()
        val background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20f * density
            setColor(0xE6255961.toInt())
        }
        val label = if (status.loading) {
            "次ページ取得中…"
        } else {
            readingPassthroughOverlayLabel(status.page, status.pageCount)
        }
        return TextView(appContext).apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
            this.background = background
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "読書$label"
            alpha = 0.94f
        }
    }

    private fun detach() {
        stopPulse()
        val view = rootView ?: run {
            visibleStatus = null
            return
        }
        rootView = null
        visibleStatus = null
        windowManager.removeView(view)
        Log.i(TAG, "Status overlay hidden")
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
