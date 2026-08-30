package com.g150446.voiceharness

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Observer
import com.vuzix.ultralite.Layout
import com.vuzix.ultralite.UltraliteSDK
import com.vuzix.ultralite.utils.scroll.TextToImageSlicer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class SmartGlassesState(
    val available: Boolean = false,
    val linked: Boolean = false,
    val connected: Boolean = false,
    val controlledByMe: Boolean = false,
    val displaying: Boolean = false,
    val deviceName: String? = null,
    val errorMessage: String? = null,
    val readingPassthroughActive: Boolean = false,
    val readingPage: Int = 0,
    val readingPageCount: Int = 0,
    val readingPageLoading: Boolean = false,
)

internal sealed interface ReadingPageAdvanceResult {
    data object Inactive : ReadingPageAdvanceResult
    data object Advanced : ReadingPageAdvanceResult
    data object AtEnd : ReadingPageAdvanceResult
    data class Failed(val message: String) : ReadingPageAdvanceResult
}

internal enum class SmartGlassesDisplayMode(
    val timeoutSeconds: Int,
    val autoRelease: Boolean,
) {
    RESPONSE(timeoutSeconds = 300, autoRelease = true),
    READING_PASSTHROUGH(timeoutSeconds = 0, autoRelease = false),
}

internal fun shouldRestoreReadingDisplay(
    readingActive: Boolean,
    available: Boolean,
    linked: Boolean,
    connected: Boolean,
    controlledByOther: Boolean,
    controlledByMe: Boolean,
    displaying: Boolean,
): Boolean = readingActive &&
    available &&
    linked &&
    connected &&
    !controlledByOther &&
    !(controlledByMe && displaying)

internal suspend fun awaitSmartGlassesControl(
    state: StateFlow<SmartGlassesState>,
    timeoutMs: Long
): Boolean {
    if (state.value.controlledByMe) return true
    return withTimeoutOrNull(timeoutMs) {
        state.first { current ->
            current.controlledByMe ||
                !current.available ||
                !current.linked ||
                !current.connected
        }.controlledByMe
    } ?: false
}

internal class SmartGlassesOutputManager(context: Context) {
    private val sdk = UltraliteSDK.get(context.applicationContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val displayMutex = Mutex()
    private val _state = MutableStateFlow(SmartGlassesState())
    val state: StateFlow<SmartGlassesState> = _state.asStateFlow()

    private val availableObserver = Observer<Boolean> { onSdkStateChanged() }
    private val linkedObserver = Observer<Boolean> { onSdkStateChanged() }
    private val connectedObserver = Observer<Boolean> { onSdkStateChanged() }
    private val controlledObserver = Observer<Boolean> { onSdkStateChanged() }
    private val controlledByMeObserver = Observer<Boolean> { onSdkStateChanged() }

    private var isClosed = false
    private var autoReleaseRunnable: Runnable? = null
    private var readingSlicer: TextToImageSlicer? = null
    private var readingLineCount = 0
    private var readingLinesPerPage = 0
    private var readingPageCount = 0
    private var readingPageIndex = 0
    private var readingRestoreEnabled = false
    private var readingRestoreJob: Job? = null

    init {
        sdk.available.observeForever(availableObserver)
        sdk.linked.observeForever(linkedObserver)
        sdk.connected.observeForever(connectedObserver)
        sdk.controlled.observeForever(controlledObserver)
        sdk.controlledByMe.observeForever(controlledByMeObserver)
        refreshState()
    }

    suspend fun displayResponse(text: String): SmartGlassesDisplayResult =
        withContext(Dispatchers.Main.immediate) {
            displayMutex.withLock {
                clearReadingSession()
                displayTextLocked(text, SmartGlassesDisplayMode.RESPONSE)
            }
        }

    suspend fun startReadingPassthrough(text: String): SmartGlassesDisplayResult =
        withContext(Dispatchers.Main.immediate) {
            displayMutex.withLock {
                readingRestoreEnabled = false
                cancelReadingRestore()
                cancelAutoRelease()
                val normalizedText = text.replace("\r\n", "\n").replace('\r', '\n').trim()
                if (normalizedText.isEmpty()) {
                    return@withLock SmartGlassesDisplayResult.Failed(
                        "表示できる画面本文がありません"
                    )
                }
                val slicer = TextToImageSlicer(
                    normalizedText,
                    READING_SLICE_HEIGHT_PX,
                    READING_FONT_SIZE_PX,
                )
                val lineCount = slicer.numLines
                if (lineCount <= 0) {
                    return@withLock SmartGlassesDisplayResult.Failed(
                        "表示できる画面本文がありません"
                    )
                }
                val linesPerPage = ReadingPageLayout.linesPerPage(
                    screenHeightPx = UltraliteSDK.Canvas.HEIGHT,
                    sliceHeightPx = READING_SLICE_HEIGHT_PX,
                )
                readingSlicer = slicer
                readingLineCount = lineCount
                readingLinesPerPage = linesPerPage
                readingPageCount = ReadingPageLayout.pageCount(lineCount, linesPerPage)
                readingPageIndex = 0
                updateReadingState()
                val result = displayReadingPageLocked(readingPageIndex)
                if (result is SmartGlassesDisplayResult.Started) {
                    readingRestoreEnabled = true
                } else {
                    clearReadingSession()
                }
                result
            }
        }

    suspend fun showNextReadingPage(): ReadingPageAdvanceResult =
        withContext(Dispatchers.Main.immediate) {
            displayMutex.withLock {
                if (readingSlicer == null || readingPageIndex >= readingPageCount - 1) {
                    return@withLock if (readingSlicer == null) {
                        ReadingPageAdvanceResult.Inactive
                    } else {
                        ReadingPageAdvanceResult.AtEnd
                    }
                }
                val nextIndex = readingPageIndex + 1
                val result = displayReadingPageLocked(nextIndex)
                if (result is SmartGlassesDisplayResult.Started) {
                    readingPageIndex = nextIndex
                    updateReadingState()
                    Log.d(
                        TAG,
                        "Advanced reading passthrough to page " +
                            "${nextIndex + 1}/$readingPageCount"
                    )
                    ReadingPageAdvanceResult.Advanced
                } else {
                    ReadingPageAdvanceResult.Failed(
                        (result as SmartGlassesDisplayResult.Failed).message
                    )
                }
            }
        }

    /** Must be called on the main dispatcher while [displayMutex] is held. */
    private suspend fun displayTextLocked(
        text: String,
        mode: SmartGlassesDisplayMode,
    ): SmartGlassesDisplayResult {
        cancelAutoRelease()
        if (isClosed) {
            return SmartGlassesDisplayResult.Failed("Z100出力は終了しています")
        }
        if (text.isBlank()) {
            return SmartGlassesDisplayResult.Failed("表示する返答がありません")
        }

        stopActiveDisplay(releaseControl = false)
        acquireControlLocked()?.let { return it }

        return try {
            sdk.setLayout(
                Layout.TEXT_BOTTOM_LEFT_ALIGN,
                mode.timeoutSeconds,
                false,
                false,
                0
            )
            if (sdk.layout != Layout.TEXT_BOTTOM_LEFT_ALIGN) {
                throw IllegalStateException("Unable to select the static text layout")
            }
            sdk.sendText(text)
            markDisplayStarted()
            Log.d(TAG, "Displayed Z100 text mode=$mode (${text.length} chars)")
            if (mode.autoRelease) {
                scheduleAutoRelease(DISPLAY_HOLD_BEFORE_RELEASE_MS)
            }
            SmartGlassesDisplayResult.Started
        } catch (error: RuntimeException) {
            displayFailure(error)
        }
    }

    /** Renders one page using Android-rasterized line images to preserve Japanese glyphs. */
    private suspend fun displayReadingPageLocked(pageIndex: Int): SmartGlassesDisplayResult {
        cancelAutoRelease()
        if (isClosed) {
            return SmartGlassesDisplayResult.Failed("Z100出力は終了しています")
        }
        val slicer = readingSlicer
            ?: return SmartGlassesDisplayResult.Failed("表示できる画面本文がありません")
        val pageRange = try {
            ReadingPageLayout.pageRange(pageIndex, readingLineCount, readingLinesPerPage)
        } catch (error: IllegalArgumentException) {
            return SmartGlassesDisplayResult.Failed("表示するページがありません", error)
        }

        // Do not switch through DEFAULT between pages: that can briefly reveal the Z100 status bar.
        acquireControlLocked()?.let { return it }

        return try {
            sdk.setLayout(
                Layout.SCROLL,
                SmartGlassesDisplayMode.READING_PASSTHROUGH.timeoutSeconds,
                true,
                false,
                0,
            )
            if (sdk.layout != Layout.SCROLL) {
                throw IllegalStateException("Unable to select the scrolling text layout")
            }
            val scrollingTextView = sdk.scrollingTextView
            scrollingTextView.scrollLayoutConfig(
                READING_SLICE_HEIGHT_PX,
                0,
                readingLinesPerPage,
                0,
                false,
            )
            scrollingTextView.clear()
            repeat(pageRange.lineCount) { localLineIndex ->
                val sourceLineIndex = pageRange.firstLine + localLineIndex
                val z100SlicePosition = readingLinesPerPage - 1 - localLineIndex
                scrollingTextView.sendScrollImage(
                    slicer.getSliceAt(sourceLineIndex),
                    z100SlicePosition,
                    false,
                )
            }
            markDisplayStarted()
            Log.d(
                TAG,
                "Displayed Z100 reading page ${pageIndex + 1}/$readingPageCount " +
                    "(${pageRange.lineCount}/$readingLinesPerPage visible lines, " +
                    "$readingLineCount total lines)"
            )
            SmartGlassesDisplayResult.Started
        } catch (error: RuntimeException) {
            displayFailure(error)
        }
    }

    /** Must be called on the main dispatcher while [displayMutex] is held. */
    private suspend fun acquireControlLocked(): SmartGlassesDisplayResult.Failed? {
        refreshStateNow()
        val current = _state.value
        val unavailableMessage = when {
            !current.available -> "Vuzix Connectを利用できません"
            !current.linked -> "Z100がリンクされていません"
            !current.connected -> "Z100が接続されていません"
            else -> null
        }
        if (unavailableMessage != null) {
            updateError(unavailableMessage)
            return SmartGlassesDisplayResult.Failed(unavailableMessage)
        }

        val alreadyControlled = sdk.isControlledByMe
        val controlRequestedAt = SystemClock.elapsedRealtime()
        val requestAccepted = try {
            alreadyControlled || sdk.requestControl()
        } catch (error: RuntimeException) {
            val message = "Z100の制御を取得できませんでした"
            Log.w(TAG, message, error)
            updateError(message)
            releaseControlSafely()
            return SmartGlassesDisplayResult.Failed(message, error)
        }
        if (!requestAccepted) {
            val message = "Z100の制御を取得できませんでした"
            updateError(message)
            return SmartGlassesDisplayResult.Failed(message)
        }
        val hasControl = alreadyControlled || awaitSmartGlassesControl(
            state = state,
            timeoutMs = CONTROL_CONFIRMATION_TIMEOUT_MS
        )
        if (!hasControl) {
            val message = if (_state.value.connected) {
                "Z100の制御取得がタイムアウトしました"
            } else {
                "Z100の接続が失われました"
            }
            updateError(message)
            releaseControlSafely()
            return SmartGlassesDisplayResult.Failed(message)
        }
        if (!alreadyControlled) {
            Log.d(
                TAG,
                "Z100 control confirmed in " +
                    "${SystemClock.elapsedRealtime() - controlRequestedAt} ms"
            )
        }
        return null
    }

    private fun markDisplayStarted() {
        _state.update {
            it.copy(
                controlledByMe = true,
                displaying = true,
                errorMessage = null,
            )
        }
    }

    private fun displayFailure(error: RuntimeException): SmartGlassesDisplayResult.Failed {
        val message = "Z100への表示を開始できませんでした"
        Log.w(TAG, message, error)
        updateError(message)
        cancelAutoRelease()
        releaseControlSafely()
        return SmartGlassesDisplayResult.Failed(message, error)
    }

    /**
     * Clears any active Z100 presentation. Safe to call on every recording start:
     * when the read-window auto-release already ran, this skips Z100 BLE traffic
     * so it does not contend with HarnessNode PCM notifications.
     */
    fun stopDisplay() {
        runOnMain {
            val wasRestoring = readingRestoreJob != null
            clearReadingSession()
            cancelAutoRelease()
            val needsTeardown = wasRestoring || _state.value.displaying || sdk.isControlledByMe
            if (needsTeardown) {
                stopActiveDisplay(releaseControl = true)
                Log.d(TAG, "Z100 display stopped")
            } else {
                Log.d(TAG, "Z100 already idle; skip BLE teardown")
            }
            refreshState()
        }
    }

    fun close() {
        runOnMain {
            if (isClosed) return@runOnMain
            isClosed = true
            clearReadingSession()
            cancelAutoRelease()
            stopActiveDisplay(releaseControl = true)
            managerScope.cancel()
            sdk.available.removeObserver(availableObserver)
            sdk.linked.removeObserver(linkedObserver)
            sdk.connected.removeObserver(connectedObserver)
            sdk.controlled.removeObserver(controlledObserver)
            sdk.controlledByMe.removeObserver(controlledByMeObserver)
        }
    }

    private fun scheduleAutoRelease(delayMs: Long) {
        cancelAutoRelease()
        val runnable = Runnable {
            autoReleaseRunnable = null
            if (isClosed) return@Runnable
            if (!_state.value.displaying && !sdk.isControlledByMe) return@Runnable
            Log.d(TAG, "Auto-releasing Z100 after ${delayMs}ms read window")
            stopActiveDisplay(releaseControl = true)
            refreshState()
        }
        autoReleaseRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelAutoRelease() {
        autoReleaseRunnable?.let { mainHandler.removeCallbacks(it) }
        autoReleaseRunnable = null
    }

    private fun stopActiveDisplay(releaseControl: Boolean) {
        if (_state.value.displaying && sdk.isControlledByMe) {
            try {
                sdk.setLayout(Layout.DEFAULT, DISPLAY_TIMEOUT_SECONDS)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Unable to clear the Z100 display", error)
            }
        }
        _state.update { it.copy(displaying = false) }
        if (releaseControl) releaseControlSafely()
    }

    private fun releaseControlSafely() {
        try {
            // releaseControl() is scoped to this SDK client. Calling it unconditionally also
            // covers a grant whose LiveData callback has not reached this process yet.
            sdk.releaseControl()
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to release Z100 control", error)
        }
    }

    private fun refreshState() {
        runOnMain {
            refreshStateNow()
        }
    }

    private fun onSdkStateChanged() {
        runOnMain {
            refreshStateNow()
            maybeRestoreReadingDisplay()
        }
    }

    private fun refreshStateNow() {
        val available = sdk.isAvailable
        val linked = available && sdk.isLinked
        val connected = linked && sdk.isConnected
        val controlled = connected && sdk.isControlledByMe
        _state.update {
            it.copy(
                available = available,
                linked = linked,
                connected = connected,
                controlledByMe = controlled,
                displaying = it.displaying && connected && controlled,
                deviceName = if (connected) sdk.name?.takeIf(String::isNotBlank) else null
            )
        }
    }

    private fun maybeRestoreReadingDisplay() {
        if (readingSlicer == null || isClosed) return
        if (!shouldRestoreReadingDisplay(
                readingActive = readingRestoreEnabled,
                available = sdk.isAvailable,
                linked = sdk.isLinked,
                connected = sdk.isConnected,
                controlledByOther = sdk.isControlled && !sdk.isControlledByMe,
                controlledByMe = sdk.isControlledByMe,
                displaying = _state.value.displaying,
            )
        ) return
        if (readingRestoreJob?.isActive == true) return

        val job = managerScope.launch(start = CoroutineStart.LAZY) {
            try {
                displayMutex.withLock {
                    if (!readingRestoreEnabled || readingSlicer == null || isClosed) {
                        return@withLock
                    }
                    if (!sdk.isAvailable || !sdk.isLinked || !sdk.isConnected) {
                        return@withLock
                    }
                    if (sdk.isControlled && !sdk.isControlledByMe) return@withLock
                    val result = displayReadingPageLocked(readingPageIndex)
                    if (result is SmartGlassesDisplayResult.Started) {
                        Log.i(
                            TAG,
                            "Restored reading passthrough page " +
                                "${readingPageIndex + 1}/$readingPageCount"
                        )
                    } else if (result is SmartGlassesDisplayResult.Failed) {
                        Log.w(TAG, "Reading passthrough restore failed: ${result.message}")
                    }
                }
            } finally {
                readingRestoreJob = null
            }
        }
        readingRestoreJob = job
        job.start()
    }

    private fun updateError(message: String) {
        _state.update { it.copy(displaying = false, errorMessage = message) }
        Log.w(TAG, message)
    }

    private fun updateReadingState() {
        val active = readingSlicer != null && readingPageCount > 0
        _state.update {
            it.copy(
                readingPassthroughActive = active,
                readingPage = if (active) readingPageIndex + 1 else 0,
                readingPageCount = if (active) readingPageCount else 0,
                readingPageLoading = if (active) it.readingPageLoading else false,
            )
        }
    }

    fun setReadingPageLoading(loading: Boolean) {
        runOnMain {
            _state.update { it.copy(readingPageLoading = loading) }
        }
    }

    private fun clearReadingSession() {
        readingRestoreEnabled = false
        cancelReadingRestore()
        readingSlicer = null
        readingLineCount = 0
        readingLinesPerPage = 0
        readingPageCount = 0
        readingPageIndex = 0
        updateReadingState()
    }

    private fun cancelReadingRestore() {
        readingRestoreJob?.cancel()
        readingRestoreJob = null
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    private companion object {
        private const val TAG = "SmartGlassesOutput"
        private const val DISPLAY_TIMEOUT_SECONDS = 300
        private const val CONTROL_CONFIRMATION_TIMEOUT_MS = 3_000L
        private const val READING_SLICE_HEIGHT_PX = 48
        private const val READING_FONT_SIZE_PX = 35
        /** Keep text readable, then drop Z100 control before the next BLE recording. */
        private const val DISPLAY_HOLD_BEFORE_RELEASE_MS = 12_000L
    }
}
