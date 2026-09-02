package com.g150446.voiceharness

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal enum class PageTurnGesture {
    /** Finger moves right → left (typical horizontal / LTR page turn). */
    SWIPE_LEFT,
    /** Finger moves left → right (typical vertical Japanese page turn). */
    SWIPE_RIGHT,
    UNKNOWN,
}

internal enum class WritingDirection {
    VERTICAL,
    HORIZONTAL,
    UNKNOWN,
}

/** Swipe attempts for Kindle page advance, preferred direction first. */
internal fun pageTurnSwipeCandidates(preferred: PageTurnGesture): List<PageTurnGesture> =
    when (preferred) {
        PageTurnGesture.SWIPE_LEFT -> listOf(PageTurnGesture.SWIPE_LEFT)
        PageTurnGesture.SWIPE_RIGHT -> listOf(PageTurnGesture.SWIPE_RIGHT)
        PageTurnGesture.UNKNOWN -> listOf(PageTurnGesture.SWIPE_LEFT, PageTurnGesture.SWIPE_RIGHT)
    }

internal enum class KindlePageTurnResult {
    DISPATCHED,
    UNAVAILABLE,
    NOT_KINDLE,
    FAILED,
}

/** Process-local bridge from VoiceProcessor to the enabled AccessibilityService. */
internal object KindlePageTurnController {
    const val KINDLE_PACKAGE = "com.amazon.kindle"

    @Volatile private var service: AccessibilityService? = null

    fun attach(accessibilityService: AccessibilityService) {
        service = accessibilityService
    }

    fun detach(accessibilityService: AccessibilityService) {
        if (service === accessibilityService) service = null
    }

    fun foregroundPackage(): String? = service?.rootInActiveWindow?.packageName?.toString()

    fun isAvailable(): Boolean = service != null

    /** Accepts bare package ids and Assist-style "package/activity" titles. */
    fun isKindlePackage(packageName: String?): Boolean {
        val value = packageName?.trim().orEmpty()
        if (value.isEmpty()) return false
        return value == KINDLE_PACKAGE ||
            value.startsWith("$KINDLE_PACKAGE/") ||
            value.startsWith("$KINDLE_PACKAGE.")
    }

    fun performSemanticNext(): KindlePageTurnResult {
        val current = service ?: return KindlePageTurnResult.UNAVAILABLE
        val root = current.rootInActiveWindow
            ?: return KindlePageTurnResult.NOT_KINDLE
        if (root.packageName?.toString() != KINDLE_PACKAGE) {
            return KindlePageTurnResult.NOT_KINDLE
        }
        val scrollable = findForwardScrollableNode(root)
            ?: return KindlePageTurnResult.FAILED
        return if (scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            KindlePageTurnResult.DISPATCHED
        } else {
            KindlePageTurnResult.FAILED
        }
    }

    suspend fun performSwipe(direction: PageTurnGesture): KindlePageTurnResult = withContext(Dispatchers.Main.immediate) {
        if (direction == PageTurnGesture.UNKNOWN) return@withContext KindlePageTurnResult.FAILED
        val current = service ?: return@withContext KindlePageTurnResult.UNAVAILABLE
        if (!isKindlePackage(foregroundPackage())) return@withContext KindlePageTurnResult.NOT_KINDLE
        val metrics = current.resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        val y = height * 0.50f
        val startX: Float
        val endX: Float
        if (direction == PageTurnGesture.SWIPE_LEFT) {
            startX = width * 0.82f
            endX = width * 0.18f
        } else {
            startX = width * 0.18f
            endX = width * 0.82f
        }
        val path = Path().apply {
            moveTo(startX, y)
            lineTo(endX, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, SWIPE_DURATION_MS))
            .build()
        suspendCancellableCoroutine { continuation ->
            val dispatched = runCatching {
                current.dispatchGesture(
                    gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) continuation.resume(KindlePageTurnResult.DISPATCHED)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) continuation.resume(KindlePageTurnResult.FAILED)
                        }
                    },
                    null,
                )
            }.getOrDefault(false)
            if (!dispatched && continuation.isActive) {
                continuation.resume(KindlePageTurnResult.FAILED)
            }
        }
    }

    private fun findForwardScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }) {
            return node
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val match = findForwardScrollableNode(child)
            if (match != null) return match
        }
        return null
    }

    private const val SWIPE_DURATION_MS = 320L
}
