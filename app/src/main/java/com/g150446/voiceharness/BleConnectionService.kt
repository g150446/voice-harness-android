package com.g150446.voiceharness

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "BleConnectionService"
private const val NOTIFICATION_ID = 1001
private const val CHANNEL_ID = "ble_connection"
private const val WAKE_LOCK_TAG = "HarnessVoice:BleConnectionWakeLock"

data class DoubleTapStatus(
    val count: Long = 0,
    val lastDetectedAtMillis: Long? = null,
)

internal fun nextDoubleTapStatus(
    current: DoubleTapStatus,
    detectedAtMillis: Long,
): DoubleTapStatus = DoubleTapStatus(
    count = current.count + 1,
    lastDetectedAtMillis = detectedAtMillis,
)

class BleConnectionService : Service() {

    companion object {
        private const val ACTION_ASSISTANT_QUERY =
            "com.g150446.voiceharness.action.ASSISTANT_QUERY"
        private const val ACTION_ASSISTANT_CANCEL =
            "com.g150446.voiceharness.action.ASSISTANT_CANCEL"
        private const val EXTRA_ASSISTANT_TEXT = "assistant_text"
        private const val EXTRA_CONVERSATION_ID = "assistant_conversation_id"
        private const val EXTRA_REQUEST_ID = "assistant_request_id"
        private const val EXTRA_SPEAK_RESPONSE = "assistant_speak_response"
        private const val EXTRA_SCREEN_TOKEN = "assistant_screen_token"
        private const val EXTRA_ORIGIN = "assistant_origin"
        // BLE state flows — independent of Service lifecycle.
        private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
        val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

        private val _scannedDevices = MutableStateFlow<List<BleDeviceInfo>>(emptyList())
        val scannedDevices: StateFlow<List<BleDeviceInfo>> = _scannedDevices.asStateFlow()

        private val _preferredDevice = MutableStateFlow<BleDeviceInfo?>(null)
        val preferredDevice: StateFlow<BleDeviceInfo?> = _preferredDevice.asStateFlow()

        private val _batteryLevel = MutableStateFlow<Int?>(null)
        val batteryLevel: StateFlow<Int?> = _batteryLevel.asStateFlow()

        private val _isPrimary = MutableStateFlow(true)
        val isPrimary: StateFlow<Boolean> = _isPrimary.asStateFlow()

        private val _doubleTapStatus = MutableStateFlow(DoubleTapStatus())
        val doubleTapStatus: StateFlow<DoubleTapStatus> = _doubleTapStatus.asStateFlow()

        // Voice processing state flows — written by VoiceProcessor, read by ViewModel for UI.
        private val _voiceState = MutableStateFlow(VoiceState.READY)
        val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

        private val _transcription = MutableStateFlow("")
        val transcription: StateFlow<String> = _transcription.asStateFlow()

        private val _response = MutableStateFlow("")
        val response: StateFlow<String> = _response.asStateFlow()

        private val _errorMessage = MutableStateFlow("")
        val errorMessage: StateFlow<String> = _errorMessage.asStateFlow()

        private val _bleMode = MutableStateFlow(false)
        val bleMode: StateFlow<Boolean> = _bleMode.asStateFlow()

        private val _responseOutputTarget = MutableStateFlow(ResponseOutputTarget.PHONE_AUDIO)
        val responseOutputTarget: StateFlow<ResponseOutputTarget> =
            _responseOutputTarget.asStateFlow()

        private val _smartGlassesState = MutableStateFlow(SmartGlassesState())
        val smartGlassesState: StateFlow<SmartGlassesState> = _smartGlassesState.asStateFlow()

        private val _lastPipelineMs = MutableStateFlow(0L)
        val lastPipelineMs: StateFlow<Long> = _lastPipelineMs.asStateFlow()

        // Internal setters used by VoiceProcessor (same module/package).
        internal fun setVoiceState(state: VoiceState) { _voiceState.value = state }
        internal fun setTranscription(text: String) { _transcription.value = text }
        internal fun setResponse(text: String) { _response.value = text }
        internal fun setErrorMessage(text: String) { _errorMessage.value = text }
        internal fun setBleMode(mode: Boolean) { _bleMode.value = mode }
        internal fun setLastPipelineMs(ms: Long) { _lastPipelineMs.value = ms }

        internal fun recordDoubleTap(detectedAtMillis: Long = System.currentTimeMillis()) {
            _doubleTapStatus.value = nextDoubleTapStatus(_doubleTapStatus.value, detectedAtMillis)
        }

        private var instance: BleConnectionService? = null

        fun sendCommand(byte: Byte) {
            instance?.bleManager?.sendToRx(byte)
        }

        fun setRole(claimPrimary: Boolean) {
            val cmd = if (claimPrimary) 0x02.toByte() else 0x03.toByte()
            instance?.bleManager?.sendToRxWithRetry(cmd)
            instance?.bleManager?.setIsPrimary(claimPrimary)
        }

        fun startScan() {
            instance?.bleManager?.startManualScan()
        }

        fun connectToDevice(address: String) {
            instance?.bleManager?.connectToDevice(address)
        }

        fun disconnectFromDevice() {
            instance?.bleManager?.disconnectManually()
        }

        fun stopSpeaking() {
            instance?.voiceProcessor?.stopSpeaking()
        }

        /** Headless entry point used by the system digital-assistant session. */
        fun submitAssistantText(context: Context, text: String, conversationId: String) {
            submitAssistantRequest(
                context = context,
                text = text,
                conversationId = conversationId,
                requestId = null,
                speakResponse = true,
                screenToken = null,
                origin = QueryOrigin.DIGITAL_ASSISTANT_VOICE,
            )
        }

        fun submitAssistantRequest(
            context: Context,
            text: String,
            conversationId: String,
            requestId: String?,
            speakResponse: Boolean,
            screenToken: String?,
            origin: QueryOrigin,
        ) {
            val intent = Intent(context, BleConnectionService::class.java).apply {
                action = ACTION_ASSISTANT_QUERY
                putExtra(EXTRA_ASSISTANT_TEXT, text)
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
                putExtra(EXTRA_REQUEST_ID, requestId)
                putExtra(EXTRA_SPEAK_RESPONSE, speakResponse)
                putExtra(EXTRA_SCREEN_TOKEN, screenToken)
                putExtra(EXTRA_ORIGIN, origin.name)
            }
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancelAssistantRequest(context: Context, requestId: String?) {
            val intent = Intent(context, BleConnectionService::class.java).apply {
                action = ACTION_ASSISTANT_CANCEL
                putExtra(EXTRA_REQUEST_ID, requestId)
            }
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        internal fun setPhonePlaybackActive(active: Boolean) {
            instance?.apply {
                setPlaybackActive(active)
                if (!active) releaseProcessingWakeLock()
            }
        }

        internal fun releaseAssistantProcessing() {
            instance?.releaseProcessingWakeLock()
        }

        fun initializeResponseOutputTarget(context: Context) {
            _responseOutputTarget.value = ResponseOutputPreferences(context).target()
        }

        fun setResponseOutputTarget(context: Context, target: ResponseOutputTarget) {
            ResponseOutputPreferences(context).setTarget(target)
            _responseOutputTarget.value = target
            if (target == ResponseOutputTarget.PHONE_AUDIO) {
                instance?.smartGlassesOutputManager?.stopDisplay()
            }
        }

        fun disconnectProcessor() {
            instance?.voiceProcessor?.disconnect()
        }

        fun switchOnDeviceProfile(context: Context, profile: OnDeviceProfile) {
            val svc = instance
            if (svc?.voiceProcessor != null) {
                svc.voiceProcessor?.switchProfile(profile)
            } else {
                ModelManager.setProfile(context.applicationContext, profile)
            }
        }

        fun switchSttBackend(context: Context, backend: SttBackendId) {
            val svc = instance
            if (svc?.voiceProcessor != null) {
                svc.voiceProcessor?.switchSttBackend(backend)
            } else {
                ModelManager.setSttBackend(context.applicationContext, backend)
            }
        }

        fun switchLlmBackend(context: Context, backend: LlmBackendId) {
            val svc = instance
            if (svc?.voiceProcessor != null) {
                svc.voiceProcessor?.switchLlmBackend(backend)
            } else {
                ModelManager.setLlmBackend(context.applicationContext, backend)
            }
        }

        /**
         * Re-creates the on-device engine so settings that are only read at engine creation
         * (MTP) take effect. Reuses the profile-switch path, which releases the active backend
         * unconditionally and warms the replacement up in the background.
         */
        fun reloadOnDeviceBackend(context: Context) {
            switchOnDeviceProfile(context, ModelManager.currentProfile(context.applicationContext))
        }

        fun start(context: Context) {
            val intent = Intent(context, BleConnectionService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BleConnectionService::class.java))
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var bleManager: BleManager? = null
    private var voiceProcessor: VoiceProcessor? = null
    private var smartGlassesOutputManager: SmartGlassesOutputManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var processingWakeLock: PowerManager.WakeLock? = null
    private var playbackActive = false
    private var recordingOverlay: RecordingOverlayController? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "Service created")
        com.g150446.voiceharness.assistant.OwnAppUiTracker.register(application)

        createNotificationChannel()
        startForegroundWithNotification("BLE: Scanning...")
        recordingOverlay = RecordingOverlayController(applicationContext)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        initializeResponseOutputTarget(applicationContext)
        smartGlassesOutputManager = SmartGlassesOutputManager(applicationContext).also { manager ->
            serviceScope.launch {
                manager.state.collect { _smartGlassesState.value = it }
            }
        }
        voiceProcessor = VoiceProcessor(
            applicationContext,
            serviceScope,
            requireNotNull(smartGlassesOutputManager)
        )
        serviceScope.launch {
            voiceState.collect { state ->
                onVoiceStateChanged(state)
            }
        }
        bleManager = BleManager(applicationContext, serviceScope).also { mgr ->
            mgr.start(bluetoothManager)

            serviceScope.launch {
                mgr.connectionState.collect { state ->
                    _connectionState.value = state
                    refreshNotification()
                    when (state) {
                        BleConnectionState.CONNECTED -> acquireWakeLock()
                        BleConnectionState.SCANNING, BleConnectionState.CONNECTING -> acquireWakeLock()
                        BleConnectionState.DISCONNECTED -> releaseWakeLock()
                    }
                }
            }
            serviceScope.launch(Dispatchers.IO) {
                mgr.voiceInputs.collect { input ->
                    if (input is BleVoiceInput.Event && input.event is BleEvent.DoubleTap) {
                        recordDoubleTap()
                        Log.i(TAG, "Double tap published to UI")
                    }
                    voiceProcessor?.handleBleInput(input)
                }
            }
            serviceScope.launch {
                mgr.scannedDevices.collect { _scannedDevices.value = it }
            }
            serviceScope.launch {
                mgr.preferredDevice.collect { _preferredDevice.value = it }
            }
            serviceScope.launch {
                mgr.batteryLevel.collect { level ->
                    _batteryLevel.value = level
                    refreshNotification()
                }
            }
            serviceScope.launch {
                mgr.isPrimary.collect { _isPrimary.value = it }
            }
        }

        ModelManager.refresh(applicationContext)

        ServiceWatchdog.schedule(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        when (intent?.action) {
            ACTION_ASSISTANT_QUERY -> {
                val text = intent.getStringExtra(EXTRA_ASSISTANT_TEXT).orEmpty()
                val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty()
                val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
                val speakResponse = intent.getBooleanExtra(EXTRA_SPEAK_RESPONSE, true)
                val screenToken = intent.getStringExtra(EXTRA_SCREEN_TOKEN)
                val origin = runCatching {
                    QueryOrigin.valueOf(
                        intent.getStringExtra(EXTRA_ORIGIN)
                            ?: QueryOrigin.DIGITAL_ASSISTANT_VOICE.name
                    )
                }.getOrDefault(QueryOrigin.DIGITAL_ASSISTANT_VOICE)
                if (text.isNotBlank() && conversationId.isNotBlank()) {
                    acquireProcessingWakeLock()
                    voiceProcessor?.handleAssistantRequest(
                        text = text,
                        conversationId = conversationId,
                        requestId = requestId,
                        speakResponse = speakResponse,
                        screenToken = screenToken,
                        origin = origin,
                    )
                }
            }
            ACTION_ASSISTANT_CANCEL -> {
                val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
                voiceProcessor?.cancelAssistantRequest(requestId)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        recordingOverlay?.hide()
        recordingOverlay = null
        voiceProcessor?.shutdown()
        bleManager?.shutdown()
        smartGlassesOutputManager?.close()
        serviceScope.cancel()
        releaseWakeLock()
        releaseProcessingWakeLock()
        _connectionState.value = BleConnectionState.DISCONNECTED
        _scannedDevices.value = emptyList()
        _batteryLevel.value = null
        _voiceState.value = VoiceState.READY
        _bleMode.value = false
        _smartGlassesState.value = SmartGlassesState()
        instance = null
    }

    private fun onVoiceStateChanged(state: VoiceState) {
        if (state == VoiceState.RECORDING) {
            recordingOverlay?.show()
        } else {
            recordingOverlay?.hide()
        }
        refreshNotification()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- Wake lock ---

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            acquire()
        }
        Log.d(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.d(TAG, "WakeLock released")
        }
        wakeLock = null
    }

    private fun acquireProcessingWakeLock() {
        if (processingWakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        processingWakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "HarnessVoice:AssistantProcessingWakeLock",
        ).apply { acquire(5 * 60 * 1000L) }
    }

    private fun releaseProcessingWakeLock() {
        if (processingWakeLock?.isHeld == true) processingWakeLock?.release()
        processingWakeLock = null
    }

    // --- Notification ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BLE Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Harness Voice BLE connection status"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Harness Voice")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

    private fun startForegroundWithNotification(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                foregroundServiceTypes()
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun setPlaybackActive(active: Boolean) {
        if (playbackActive == active) return
        playbackActive = active
        refreshNotification(forceForeground = true)
    }

    private fun foregroundServiceTypes(): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (playbackActive && Build.VERSION.SDK_INT >= 29) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
        return types
    }

    private fun notificationText(): String {
        if (_voiceState.value == VoiceState.RECORDING) return "録音中…"
        if (playbackActive) return "AI response playing"
        return when (_connectionState.value) {
            BleConnectionState.SCANNING -> "BLE: Scanning..."
            BleConnectionState.CONNECTING -> "BLE: Connecting..."
            BleConnectionState.CONNECTED -> _batteryLevel.value?.let { "BLE: Connected  Battery: $it%" }
                ?: "BLE: Connected"
            BleConnectionState.DISCONNECTED -> "BLE: Disconnected"
        }
    }

    private fun refreshNotification(forceForeground: Boolean = false) {
        val text = notificationText()
        if (forceForeground) {
            startForegroundWithNotification(text)
            return
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
