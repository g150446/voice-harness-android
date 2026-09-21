package com.g150446.voiceharness

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.Display
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
internal fun pageTurnSwipeCandidates(
    preferred: PageTurnGesture,
    forward: Boolean = true,
): List<PageTurnGesture> {
    val next = when (preferred) {
        PageTurnGesture.SWIPE_LEFT -> listOf(PageTurnGesture.SWIPE_LEFT)
        PageTurnGesture.SWIPE_RIGHT -> listOf(PageTurnGesture.SWIPE_RIGHT)
        PageTurnGesture.UNKNOWN -> listOf(PageTurnGesture.SWIPE_LEFT, PageTurnGesture.SWIPE_RIGHT)
    }
    if (forward) return next
    return next.map(::oppositePageTurn).distinct()
}

internal fun oppositePageTurn(gesture: PageTurnGesture): PageTurnGesture = when (gesture) {
    PageTurnGesture.SWIPE_LEFT -> PageTurnGesture.SWIPE_RIGHT
    PageTurnGesture.SWIPE_RIGHT -> PageTurnGesture.SWIPE_LEFT
    PageTurnGesture.UNKNOWN -> PageTurnGesture.UNKNOWN
}

/** Where a page-turn swipe is sent: a display and the area (in that display's pixels) Kindle occupies. */
internal data class SwipeTarget(val displayId: Int, val left: Int, val top: Int, val width: Int, val height: Int)

internal data class SwipeLine(val startX: Float, val endX: Float, val y: Float)

/** Horizontal swipe across the middle of [target]; SWIPE_LEFT moves the finger right → left. */
internal fun pageTurnSwipeLine(direction: PageTurnGesture, target: SwipeTarget): SwipeLine? {
    val fromRight = when (direction) {
        PageTurnGesture.SWIPE_LEFT -> true
        PageTurnGesture.SWIPE_RIGHT -> false
        PageTurnGesture.UNKNOWN -> return null
    }
    val near = target.left + target.width * 0.18f
    val far = target.left + target.width * 0.82f
    return SwipeLine(
        startX = if (fromRight) far else near,
        endX = if (fromRight) near else far,
        y = target.top + target.height * 0.50f,
    )
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

    fun performSemanticNext(): KindlePageTurnResult = performSemanticScroll(forward = true)

    fun performSemanticScroll(forward: Boolean): KindlePageTurnResult {
        val current = service ?: return KindlePageTurnResult.UNAVAILABLE
        val root = current.rootInActiveWindow
            ?: return KindlePageTurnResult.NOT_KINDLE
        if (root.packageName?.toString() != KINDLE_PACKAGE) {
            return KindlePageTurnResult.NOT_KINDLE
        }
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        val scrollable = findScrollableNode(root, action) ?: return KindlePageTurnResult.FAILED
        return if (scrollable.performAction(action)) {
            KindlePageTurnResult.DISPATCHED
        } else {
            KindlePageTurnResult.FAILED
        }
    }

    /**
     * The display Kindle is on and the area it covers. On a foldable the book can be on the cover
     * display while the default display is off; a gesture sent to the default display then lands
     * nowhere yet still reports "completed".
     */
    private fun kindleSwipeTarget(current: AccessibilityService): SwipeTarget {
        if (Build.VERSION.SDK_INT >= 30) {
            val displays = current.windowsOnAllDisplays
            for (index in 0 until displays.size()) {
                val displayId = displays.keyAt(index)
                for (window in displays.valueAt(index)) {
                    val root = window.root ?: continue
                    val isKindle = isKindlePackage(root.packageName?.toString())
                    @Suppress("DEPRECATION") root.recycle()
                    if (!isKindle) continue
                    val bounds = Rect().also(window::getBoundsInScreen)
                    if (bounds.width() > 0 && bounds.height() > 0) {
                        return SwipeTarget(displayId, bounds.left, bounds.top, bounds.width(), bounds.height())
                    }
                }
            }
        }
        val metrics = current.resources.displayMetrics
        return SwipeTarget(Display.DEFAULT_DISPLAY, 0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    suspend fun performSwipe(direction: PageTurnGesture): KindlePageTurnResult = withContext(Dispatchers.Main.immediate) {
        if (direction == PageTurnGesture.UNKNOWN) return@withContext KindlePageTurnResult.FAILED
        val current = service ?: return@withContext KindlePageTurnResult.UNAVAILABLE
        if (!isKindlePackage(foregroundPackage())) return@withContext KindlePageTurnResult.NOT_KINDLE
        val target = kindleSwipeTarget(current)
        val line = pageTurnSwipeLine(direction, target) ?: return@withContext KindlePageTurnResult.FAILED
        val path = Path().apply {
            moveTo(line.startX, line.y)
            lineTo(line.endX, line.y)
        }
        val builder = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, SWIPE_DURATION_MS))
        if (Build.VERSION.SDK_INT >= 30) builder.setDisplayId(target.displayId)
        val gesture = builder.build()
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

    private fun findScrollableNode(
        node: AccessibilityNodeInfo,
        action: Int,
    ): AccessibilityNodeInfo? {
        if (node.actionList.any { it.id == action }) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val match = findScrollableNode(child, action)
            if (match != null) return match
        }
        return null
    }

    private const val SWIPE_DURATION_MS = 320L
}
