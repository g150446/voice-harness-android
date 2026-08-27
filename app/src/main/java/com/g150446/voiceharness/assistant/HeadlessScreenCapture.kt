package com.g150446.voiceharness.assistant

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import com.g150446.voiceharness.ScreenContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Captures assist text + screenshot via a headless VoiceInteraction session
 * (no assistant Activity). Used by HarnessNode voice path.
 */
object HeadlessScreenCapture {
    const val ARG_HEADLESS = "harness_headless_capture"
    private const val TAG = "HeadlessScreenCapture"
    private const val WAIT_MS = 700L

    private val gate = Mutex()
    private val inFlight = AtomicBoolean(false)

    @Volatile private var active = false
    @Volatile private var finishSession: (() -> Unit)? = null
    private var resultDeferred: CompletableDeferred<ScreenContext?>? = null
    private var appPackageName: String? = null
    private var pendingAssistText: String? = null
    private var pendingSourcePackage: String? = null
    private var pendingSourceUri: String? = null
    private var pendingJpeg: ByteArray? = null
    private var assistReceived = false
    private var screenshotReceived = false

    fun isActive(): Boolean = active

    /**
     * Best-effort screen snapshot. Returns null when ineligible, timed out,
     * own-app UI, locked, screen off, or capture failed.
     */
    suspend fun capture(context: Context): ScreenContext? {
        val app = context.applicationContext
        if (!isEligible(app)) {
            Log.d(TAG, "Skip capture: ineligible")
            return null
        }
        if (!inFlight.compareAndSet(false, true)) {
            Log.d(TAG, "Skip capture: already in flight")
            return null
        }
        return try {
            gate.withLock {
                resetLocked()
                val deferred = CompletableDeferred<ScreenContext?>()
                resultDeferred = deferred
                appPackageName = app.packageName
                active = true
                val started = HarnessVoiceInteractionService.requestHeadlessCapture()
                if (!started) {
                    Log.d(TAG, "showSession unavailable")
                    resetLocked()
                    return@withLock null
                }
                withTimeoutOrNull(WAIT_MS) { deferred.await() }
                if (!deferred.isCompleted) {
                    deferred.complete(buildFilteredContext())
                }
                val result = deferred.await()
                finishSessionLocked()
                resetLocked()
                result
            }
        } finally {
            inFlight.set(false)
        }
    }

    fun onSessionShown(onFinish: () -> Unit) {
        finishSession = onFinish
        Log.i(TAG, "Headless session shown")
        maybeCompleteIfReady()
    }

    fun onSessionDestroyed() {
        val deferred = resultDeferred
        if (deferred != null && !deferred.isCompleted) {
            deferred.complete(buildFilteredContext())
        }
        active = false
        finishSession = null
    }

    fun onHandleAssist(
        data: Bundle?,
        structure: android.app.assist.AssistStructure?,
    ) {
        if (!active) return
        val extracted = AssistStructureExtractor.extract(structure)
        pendingAssistText = extracted.text.takeIf { it.isNotBlank() }
        pendingSourcePackage = extracted.sourcePackage
            ?: data?.getString(ASSIST_PACKAGE_KEY)
        pendingSourceUri = extracted.sourceUri
        assistReceived = true
        Log.d(
            TAG,
            "Assist textLen=${pendingAssistText?.length ?: 0} pkg=$pendingSourcePackage",
        )
        maybeCompleteIfReady()
    }

    fun onHandleScreenshot(screenshot: Bitmap?) {
        if (!active) return
        pendingJpeg = ScreenshotEncoder.toJpeg(screenshot)
        screenshotReceived = true
        Log.d(TAG, "Screenshot bytes=${pendingJpeg?.size ?: 0}")
        maybeCompleteIfReady()
    }

    private fun maybeCompleteIfReady() {
        if (!active) return
        if (!assistReceived || !screenshotReceived) return
        val deferred = resultDeferred ?: return
        if (deferred.isCompleted) return
        deferred.complete(buildFilteredContext())
        finishSessionLocked()
    }

    private fun buildFilteredContext(): ScreenContext? {
        val ownPkg = appPackageName
        val src = pendingSourcePackage
        if (!ownPkg.isNullOrBlank() && src == ownPkg) {
            Log.d(TAG, "Discard: own package screen")
            return null
        }
        if (OwnAppUiTracker.isOwnUiShowing()) {
            Log.d(TAG, "Discard: own UI showing")
            return null
        }
        val ctx = ScreenContext(
            assistText = pendingAssistText,
            sourcePackage = pendingSourcePackage,
            sourceUri = pendingSourceUri,
            jpegBytes = pendingJpeg,
            capturedAt = System.currentTimeMillis(),
        )
        return if (ctx.isEmpty) null else ctx
    }

    private fun finishSessionLocked() {
        val finisher = finishSession
        finishSession = null
        runCatching { finisher?.invoke() }
            .onFailure { Log.w(TAG, "finishSession failed", it) }
    }

    private fun resetLocked() {
        active = false
        finishSession = null
        resultDeferred = null
        appPackageName = null
        pendingAssistText = null
        pendingSourcePackage = null
        pendingSourceUri = null
        pendingJpeg = null
        assistReceived = false
        screenshotReceived = false
    }

    private fun isEligible(context: Context): Boolean {
        if (!AssistantRoleManager.isHeld(context)) return false
        if (isDeviceLocked(context)) return false
        if (!isInteractive(context)) return false
        if (OwnAppUiTracker.isOwnUiShowing()) return false
        if (AssistantSessionController.uiState.value.sessionActive) return false
        if (active) return false
        return true
    }

    private fun isDeviceLocked(context: Context): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return km?.isKeyguardLocked == true
    }

    private fun isInteractive(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return pm?.isInteractive == true
    }

    private const val ASSIST_PACKAGE_KEY = "android.intent.extra.ASSIST_PACKAGE"
}
