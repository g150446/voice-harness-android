package com.g150446.voiceharness

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Observer
import com.vuzix.ultralite.Layout
import com.vuzix.ultralite.UltraliteSDK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class SmartGlassesState(
    val available: Boolean = false,
    val linked: Boolean = false,
    val connected: Boolean = false,
    val controlledByMe: Boolean = false,
    val displaying: Boolean = false,
    val deviceName: String? = null,
    val errorMessage: String? = null
)

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
    private val _state = MutableStateFlow(SmartGlassesState())
    val state: StateFlow<SmartGlassesState> = _state.asStateFlow()

    private val availableObserver = Observer<Boolean> { refreshState() }
    private val linkedObserver = Observer<Boolean> { refreshState() }
    private val connectedObserver = Observer<Boolean> { refreshState() }
    private val controlledObserver = Observer<Boolean> { refreshState() }

    private var isClosed = false
    private var autoReleaseRunnable: Runnable? = null

    init {
        sdk.available.observeForever(availableObserver)
        sdk.linked.observeForever(linkedObserver)
        sdk.connected.observeForever(connectedObserver)
        sdk.controlledByMe.observeForever(controlledObserver)
        refreshState()
    }

    suspend fun displayResponse(text: String): SmartGlassesDisplayResult =
        withContext(Dispatchers.Main.immediate) {
            if (isClosed) {
                return@withContext SmartGlassesDisplayResult.Failed("Z100出力は終了しています")
            }
            if (text.isBlank()) {
                return@withContext SmartGlassesDisplayResult.Failed("表示する返答がありません")
            }

            refreshState()
            val current = _state.value
            val unavailableMessage = when {
                !current.available -> "Vuzix Connectを利用できません"
                !current.linked -> "Z100がリンクされていません"
                !current.connected -> "Z100が接続されていません"
                else -> null
            }
            if (unavailableMessage != null) {
                updateError(unavailableMessage)
                return@withContext SmartGlassesDisplayResult.Failed(unavailableMessage)
            }

            stopActiveDisplay(releaseControl = false)
            val alreadyControlled = sdk.isControlledByMe
            val controlRequestedAt = SystemClock.elapsedRealtime()
            val requestAccepted = try {
                alreadyControlled || sdk.requestControl()
            } catch (error: RuntimeException) {
                val message = "Z100の制御を取得できませんでした"
                Log.w(TAG, message, error)
                updateError(message)
                releaseControlSafely()
                return@withContext SmartGlassesDisplayResult.Failed(message, error)
            }
            if (!requestAccepted) {
                val message = "Z100の制御を取得できませんでした"
                updateError(message)
                return@withContext SmartGlassesDisplayResult.Failed(message)
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
                return@withContext SmartGlassesDisplayResult.Failed(message)
            }
            if (!alreadyControlled) {
                Log.d(
                    TAG,
                    "Z100 control confirmed in " +
                        "${SystemClock.elapsedRealtime() - controlRequestedAt} ms"
                )
            }

            try {
                sdk.setLayout(
                    Layout.TEXT_BOTTOM_LEFT_ALIGN,
                    DISPLAY_TIMEOUT_SECONDS,
                    false,
                    false,
                    0
                )
                if (sdk.layout != Layout.TEXT_BOTTOM_LEFT_ALIGN) {
                    throw IllegalStateException("Unable to select the static text layout")
                }
                sdk.sendText(text)
                _state.update {
                    it.copy(
                        controlledByMe = true,
                        displaying = true,
                        errorMessage = null
                    )
                }
                Log.d(TAG, "Displayed complete Z100 response (${text.length} chars)")
                scheduleAutoRelease(DISPLAY_HOLD_BEFORE_RELEASE_MS)
                SmartGlassesDisplayResult.Started
            } catch (error: RuntimeException) {
                val message = "Z100への表示を開始できませんでした"
                Log.w(TAG, message, error)
                updateError(message)
                cancelAutoRelease()
                releaseControlSafely()
                SmartGlassesDisplayResult.Failed(message, error)
            }
        }

    /**
     * Clears any active Z100 presentation. Safe to call on every recording start:
     * when the read-window auto-release already ran, this skips Z100 BLE traffic
     * so it does not contend with HarnessNode PCM notifications.
     */
    fun stopDisplay() {
        runOnMain {
            cancelAutoRelease()
            val needsTeardown = _state.value.displaying || sdk.isControlledByMe
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
            cancelAutoRelease()
            stopActiveDisplay(releaseControl = true)
            sdk.available.removeObserver(availableObserver)
            sdk.linked.removeObserver(linkedObserver)
            sdk.connected.removeObserver(connectedObserver)
            sdk.controlledByMe.removeObserver(controlledObserver)
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
    }

    private fun updateError(message: String) {
        _state.update { it.copy(displaying = false, errorMessage = message) }
        Log.w(TAG, message)
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
        /** Keep text readable, then drop Z100 control before the next BLE recording. */
        private const val DISPLAY_HOLD_BEFORE_RELEASE_MS = 12_000L
    }
}
