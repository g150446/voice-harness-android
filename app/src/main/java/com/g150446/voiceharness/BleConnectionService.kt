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
import android.app.PendingIntent
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

data class SingleTapStatus(
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

internal fun nextSingleTapStatus(
    current: SingleTapStatus,
    detectedAtMillis: Long,
): SingleTapStatus = SingleTapStatus(
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
        private val _singleTapStatus = MutableStateFlow(SingleTapStatus())
        val singleTapStatus: StateFlow<SingleTapStatus> = _singleTapStatus.asStateFlow()
        // _drivingMode is the app's *intent* (DrivingModeController's verdict plus any
        // manual override). The two below are what the node reported over 0x40, so a
        // disagreement or a deferred switch is visible instead of assumed away.
        private val _drivingMode = MutableStateFlow(DrivingMode.NORMAL)
        val drivingMode: StateFlow<DrivingMode> = _drivingMode.asStateFlow()

        /** Mode the node is actually running, or null while unconfirmed. */
        private val _nodeDrivingMode = MutableStateFlow<DrivingMode?>(null)
        val nodeDrivingMode: StateFlow<DrivingMode?> = _nodeDrivingMode.asStateFlow()

        /** Mode the node deferred until the current recording ends, or null if none. */
        private val _nodePendingDrivingMode = MutableStateFlow<DrivingMode?>(null)
        val nodePendingDrivingMode: StateFlow<DrivingMode?> =
            _nodePendingDrivingMode.asStateFlow()

        private fun drivingModeOrNull(raw: Int): DrivingMode? = when (raw) {
            0x00 -> DrivingMode.NORMAL
            0x01 -> DrivingMode.DRIVING
            else -> null
        }

        internal fun onOperationModeAck(ack: BleEvent.OperationModeAck) {
            val effective = drivingModeOrNull(ack.effective)
            val pending = drivingModeOrNull(ack.pending)
            _nodeDrivingMode.value = effective
            _nodePendingDrivingMode.value = pending
            if (pending == null && effective != null && effective != _drivingMode.value) {
                // Do not auto-resend: the CONNECTED handler already re-sends the mode,
                // and resending here could ping-pong with the node.
                Log.w(TAG, "Driving mode mismatch: app=${_drivingMode.value} node=$effective")
            }
        }

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

        private val _readingPassthroughEnabled = MutableStateFlow(false)
        val readingPassthroughEnabled: StateFlow<Boolean> =
            _readingPassthroughEnabled.asStateFlow()

        private val _interactionMode = MutableStateFlow(InteractionMode.AI)
        val interactionMode: StateFlow<InteractionMode> = _interactionMode.asStateFlow()

        private val _harborConnectionState = MutableStateFlow(HarborConnectionState())
        val harborConnectionState: StateFlow<HarborConnectionState> =
            _harborConnectionState.asStateFlow()

        private val _lastPipelineMs = MutableStateFlow(0L)
        val lastPipelineMs: StateFlow<Long> = _lastPipelineMs.asStateFlow()

        /** What the app asked the node to do about trajectory capture. */
        private val _gestureCaptureEnabled = MutableStateFlow(false)
        val gestureCaptureEnabled: StateFlow<Boolean> = _gestureCaptureEnabled.asStateFlow()

        /** What the node reported over 0x39, or null while unconfirmed. */
        private val _nodeGestureCaptureEnabled = MutableStateFlow<Boolean?>(null)
        val nodeGestureCaptureEnabled: StateFlow<Boolean?> =
            _nodeGestureCaptureEnabled.asStateFlow()

        /** Wrist-gesture start/stop; default off (tap-only). */
        private val _gestureDetectEnabled = MutableStateFlow(false)
        val gestureDetectEnabled: StateFlow<Boolean> = _gestureDetectEnabled.asStateFlow()

        /** What the node reported over 0x3A, or null while unconfirmed. */
        private val _nodeGestureDetectEnabled = MutableStateFlow<Boolean?>(null)
        val nodeGestureDetectEnabled: StateFlow<Boolean?> =
            _nodeGestureDetectEnabled.asStateFlow()

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

        internal fun recordSingleTap(detectedAtMillis: Long = System.currentTimeMillis()) {
            _singleTapStatus.value = nextSingleTapStatus(_singleTapStatus.value, detectedAtMillis)
        }

        private var instance: BleConnectionService? = null

        fun sendCommand(byte: Byte) {
            instance?.bleManager?.sendToRx(byte)
        }

        fun initializeGestureCaptureEnabled(context: Context) {
            _gestureCaptureEnabled.value = GestureCapturePreferences(context).enabled()
        }

        fun setGestureCaptureEnabled(context: Context, enabled: Boolean) {
            GestureCapturePreferences(context).setEnabled(enabled)
            _gestureCaptureEnabled.value = enabled
            sendGestureCapture(enabled)
        }

        /** Re-asserts the switch; the node holds it in RAM only. */
        private fun sendGestureCapture(enabled: Boolean) {
            instance?.bleManager?.sendToRx(byteArrayOf(0x06, if (enabled) 0x01 else 0x00))
        }

        internal fun onGestureCaptureAck(ack: BleEvent.GestureCaptureAck) {
            _nodeGestureCaptureEnabled.value = ack.enabled
            if (ack.enabled != _gestureCaptureEnabled.value) {
                // A lean firmware build reports off no matter what we ask, so say
                // which side disagrees instead of silently retrying forever.
                Log.w(
                    TAG,
                    "Gesture capture mismatch: app=${_gestureCaptureEnabled.value} " +
                        "node=${ack.enabled}",
                )
            }
        }

        fun initializeGestureDetectEnabled(context: Context) {
            _gestureDetectEnabled.value = GestureDetectPreferences(context).enabled()
        }

        fun setGestureDetectEnabled(context: Context, enabled: Boolean) {
            GestureDetectPreferences(context).setEnabled(enabled)
            _gestureDetectEnabled.value = enabled
            sendGestureDetect(enabled)
        }

        /** Re-asserts the switch; the node holds it in RAM only. */
        private fun sendGestureDetect(enabled: Boolean) {
            instance?.bleManager?.sendToRx(byteArrayOf(0x07, if (enabled) 0x01 else 0x00))
        }

        internal fun onGestureDetectAck(ack: BleEvent.GestureDetectAck) {
            _nodeGestureDetectEnabled.value = ack.enabled
            if (ack.enabled != _gestureDetectEnabled.value) {
                Log.w(
                    TAG,
                    "Gesture detect mismatch: app=${_gestureDetectEnabled.value} " +
                        "node=${ack.enabled}",
                )
            }
        }

        fun setDrivingMode(context: Context, mode: DrivingMode) {
            _drivingMode.value = mode
            instance?.bleManager?.sendToRx(byteArrayOf(0x05, if (mode == DrivingMode.DRIVING) 0x01 else 0x00))
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

        fun initializeReadingPassthroughEnabled(context: Context) {
            // Interaction mode deliberately returns to AI after a service restart.
            ReadingPassthroughPreferences(context).setEnabled(false)
            _readingPassthroughEnabled.value = false
            _interactionMode.value = InteractionMode.AI
            EvenG2ReadingSession.setEnabled(false)
        }

        /**
         * @return true when the requested state was applied (or already matched).
         * Enabling requires an active Even G2 Voice Harness plugin.
         */
        fun setReadingPassthroughEnabled(
            context: Context,
            enabled: Boolean,
            notifyG2: Boolean = true,
        ): Boolean {
            if (enabled && !EvenG2ReadingSession.isClientActive()) {
                Log.i(TAG, "Reader mode enable refused: G2 plugin not connected")
                return false
            }
            if (_readingPassthroughEnabled.value == enabled) {
                if (enabled) EvenG2ReadingSession.setEnabled(true)
                return true
            }
            ReadingPassthroughPreferences(context).setEnabled(enabled)
            _readingPassthroughEnabled.value = enabled
            EvenG2ReadingSession.setEnabled(enabled)
            if (notifyG2 && EvenG2ReadingSession.isClientActive()) {
                EvenG2ReadingSession.publishReaderModeStatus(enabled)
            } else if (!enabled) {
                EvenG2ReadingSession.clearDisplay()
            }
            instance?.publishEvenG2UiState()
            if (!enabled) setErrorMessage("")
            return true
        }

        fun setInteractionMode(context: Context, mode: InteractionMode): Boolean {
            val service = instance
            if (mode != InteractionMode.AI && !EvenG2ReadingSession.isClientActive()) {
                setErrorMessage("G2プラグインの接続が必要です")
                return false
            }
            if (mode == InteractionMode.HARBOR && _harborConnectionState.value.paired.not()) {
                setErrorMessage("Terminal Harborをペアリングしてください")
                return false
            }
            when (mode) {
                InteractionMode.AI -> {
                    setReadingPassthroughEnabled(context, false, notifyG2 = false)
                    setResponseOutputTarget(context, ResponseOutputTarget.SMART_GLASSES)
                    EvenG2ReadingSession.publishResponse("AI対話モード")
                }
                InteractionMode.READER -> {
                    service?.harborMirrorController?.setMode(InteractionMode.AI)
                    if (!setReadingPassthroughEnabled(context, true, notifyG2 = false)) return false
                    setResponseOutputTarget(context, ResponseOutputTarget.SMART_GLASSES)
                    EvenG2ReadingSession.publishReaderModeStatus(true)
                }
                InteractionMode.HARBOR -> {
                    setReadingPassthroughEnabled(context, false, notifyG2 = false)
                    service?.harborMirrorController?.setMode(InteractionMode.HARBOR)
                    EvenG2ReadingSession.publishHarbor(null, null, "Terminal Harborに接続中…")
                }
            }
            if (mode != InteractionMode.HARBOR) service?.harborMirrorController?.setMode(mode)
            _interactionMode.value = mode
            setErrorMessage("")
            service?.publishEvenG2UiState()
            return true
        }

        fun pairTerminalHarbor(rawUri: String) {
            instance?.harborMirrorController?.pair(rawUri)
        }

        fun clearTerminalHarborPairing() {
            instance?.harborMirrorController?.clear()
            if (_interactionMode.value == InteractionMode.HARBOR) {
                instance?.let { setInteractionMode(it.applicationContext, InteractionMode.AI) }
            }
        }

        internal fun pauseHarborMirror(paused: Boolean) {
            val controller = instance?.harborMirrorController ?: return
            if (paused) controller.setG2Active(false)
            else controller.setMode(_interactionMode.value)
        }

        /** Drop reader mode when the G2 plugin stops polling (no auto-ON). */
        fun syncReaderModeWithG2Client(context: Context) {
            if (!_readingPassthroughEnabled.value) return
            if (EvenG2ReadingSession.isClientActive()) return
            Log.i(TAG, "Reader mode auto-off: G2 plugin inactive")
            setReadingPassthroughEnabled(context, false, notifyG2 = false)
            _interactionMode.value = InteractionMode.AI
        }

        /** Kindle became the foreground app while accessibility is watching. */
        fun onKindleBecameForeground() {
            if (!_readingPassthroughEnabled.value) return
            instance?.voiceProcessor?.onKindleBecameForeground()
        }

        fun setResponseOutputTarget(context: Context, target: ResponseOutputTarget) {
            ResponseOutputPreferences(context).setTarget(target)
            _responseOutputTarget.value = target
            if (target == ResponseOutputTarget.PHONE_AUDIO) {
                EvenG2ReadingSession.clearDisplay()
                instance?.publishEvenG2UiState()
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
    private var wakeLock: PowerManager.WakeLock? = null
    private var processingWakeLock: PowerManager.WakeLock? = null
    private var playbackActive = false
    private var recordingOverlay: RecordingOverlayController? = null
    private var evenG2BridgeServer: EvenG2BridgeServer? = null
    private var harborMirrorController: HarborMirrorController? = null
    private lateinit var drivingModeController: DrivingModeController

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "Service created")
        com.g150446.voiceharness.assistant.OwnAppUiTracker.register(application)

        createNotificationChannel()
        startForegroundWithNotification("BLE: Scanning...")
        evenG2BridgeServer = EvenG2BridgeServer(
            statusProvider = { _doubleTapStatus.value },
            readingProvider = {
                EvenG2ReadingSession.snapshot(
                    doubleTapCount = _doubleTapStatus.value.count,
                    singleTapCount = _singleTapStatus.value.count,
                )
            },
            onAdvance = { revision ->
                val processor = instance?.voiceProcessor
                if (processor == null) {
                    EvenG2AdvanceResponse(503, "Voice processor is unavailable", false)
                } else {
                    val result = EvenG2ReadingSession.beginAdvance(revision)
                    if (result.accepted) {
                        processor.requestEvenG2PageAdvance(revision)
                    }
                    result
                }
            },
        ).also { it.start() }
        harborMirrorController = HarborMirrorController(applicationContext, serviceScope).also { controller ->
            serviceScope.launch {
                controller.state.collect { _harborConnectionState.value = it }
            }
        }
        _interactionMode.value = InteractionMode.AI
        recordingOverlay = RecordingOverlayController(applicationContext)
        drivingModeController = DrivingModeController(applicationContext)
        _drivingMode.value = drivingModeController.mode.value
        drivingModeController.start()

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        initializeResponseOutputTarget(applicationContext)
        initializeReadingPassthroughEnabled(applicationContext)
        initializeGestureCaptureEnabled(applicationContext)
        initializeGestureDetectEnabled(applicationContext)
        // Create it up front: `run-as ... tar c files/gesture_trajectories` emits a
        // corrupt archive with no clear error when the directory is missing, and
        // that would surface at the end of a collection day.
        GestureTrajectoryStore.directory(applicationContext)
        // Z100 SmartGlassesOutputManager is intentionally not started.
        // Keep the class and Ultralite SDK dependency for a possible future rewire.
        publishEvenG2UiState()
        serviceScope.launch {
            while (true) {
                kotlinx.coroutines.delay(500)
                syncReaderModeWithG2Client(applicationContext)
                harborMirrorController?.setG2Active(EvenG2ReadingSession.isClientActive())
                publishEvenG2UiState()
            }
        }
        voiceProcessor = VoiceProcessor(applicationContext, serviceScope)
        serviceScope.launch {
            voiceState.collect { state ->
                onVoiceStateChanged(state)
            }
        }
        bleManager = BleManager(applicationContext, serviceScope).also { mgr ->
            mgr.start(bluetoothManager)

            serviceScope.launch {
                drivingModeController.mode.collect { mode ->
                    _drivingMode.value = mode
                    if (stateIsConnected()) setDrivingMode(applicationContext, mode)
                    refreshNotification()
                }
            }

            serviceScope.launch {
                mgr.connectionState.collect { state ->
                    _connectionState.value = state
                    refreshNotification()
                    when (state) {
                        BleConnectionState.CONNECTED -> {
                            acquireWakeLock()
                            setDrivingMode(applicationContext, drivingModeController.mode.value)
                            sendGestureCapture(_gestureCaptureEnabled.value)
                            sendGestureDetect(_gestureDetectEnabled.value)
                        }
                        BleConnectionState.SCANNING, BleConnectionState.CONNECTING -> acquireWakeLock()
                        BleConnectionState.DISCONNECTED -> {
                            releaseWakeLock()
                            _nodeDrivingMode.value = null
                            _nodePendingDrivingMode.value = null
                            _nodeGestureCaptureEnabled.value = null
                            _nodeGestureDetectEnabled.value = null
                            // Drop phantom RECORDING/SPEAKING so scan/reconnect UI stays usable
                            // and the recording overlay cannot stick after a link drop.
                            voiceProcessor?.onBleLinkLost()
                        }
                    }
                }
            }
            serviceScope.launch(Dispatchers.IO) {
                mgr.voiceInputs.collect { input ->
                    if (input is BleVoiceInput.Event && input.event is BleEvent.DoubleTap) {
                        recordDoubleTap()
                        Log.i(TAG, "Double tap published to UI")
                        voiceProcessor?.handleDoubleTap()
                    } else if (input is BleVoiceInput.Event && input.event is BleEvent.SingleTap) {
                        recordSingleTap()
                        Log.i(TAG, "Single tap published to UI")
                        voiceProcessor?.handleSingleTap()
                    } else if (input is BleVoiceInput.Event) {
                        val event = input.event
                        if (event is BleEvent.OperationModeAck) onOperationModeAck(event)
                        if (event is BleEvent.GestureCaptureAck) onGestureCaptureAck(event)
                        if (event is BleEvent.GestureDetectAck) onGestureDetectAck(event)
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
            DrivingModeController.ACTION_SET_MODE -> {
                when (intent.getStringExtra(DrivingModeController.EXTRA_MODE)) {
                    "driving" -> drivingModeController.setOverride(DrivingMode.DRIVING)
                    "normal" -> drivingModeController.setOverride(DrivingMode.NORMAL)
                    "auto" -> drivingModeController.setOverride(null)
                }
            }
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
        evenG2BridgeServer?.close()
        evenG2BridgeServer = null
        harborMirrorController?.setMode(InteractionMode.AI)
        harborMirrorController = null
        voiceProcessor?.shutdown()
        if (::drivingModeController.isInitialized) drivingModeController.stop()
        bleManager?.shutdown()
        EvenG2ReadingSession.clearDisplay()
        serviceScope.cancel()
        releaseWakeLock()
        releaseProcessingWakeLock()
        _connectionState.value = BleConnectionState.DISCONNECTED
        _scannedDevices.value = emptyList()
        _batteryLevel.value = null
        _voiceState.value = VoiceState.READY
        _bleMode.value = false
        _smartGlassesState.value = SmartGlassesState()
        _interactionMode.value = InteractionMode.AI
        instance = null
    }

    private fun publishEvenG2UiState() {
        val next = EvenG2ReadingSession.uiSmartGlassesState(_doubleTapStatus.value.count)
        if (_smartGlassesState.value != next) {
            _smartGlassesState.value = next
            refreshStatusOverlay()
        }
    }

    private fun onVoiceStateChanged(state: VoiceState) {
        refreshStatusOverlay(state)
        refreshNotification()
    }

    private fun refreshStatusOverlay(voiceState: VoiceState = _voiceState.value) {
        recordingOverlay?.show(
            overlayStatusFor(
                voiceState = voiceState,
                glassesState = _smartGlassesState.value,
            )
        )
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
            .addAction(NotificationCompat.Action(0, if (_drivingMode.value == DrivingMode.DRIVING) "運転中" else "運転判定", modePendingIntent("auto")))
            .addAction(NotificationCompat.Action(0, "通常", modePendingIntent("normal")))
            .addAction(NotificationCompat.Action(0, "運転", modePendingIntent("driving")))
            .build()

    private fun modePendingIntent(mode: String) = PendingIntent.getService(
        this,
        mode.hashCode(),
        Intent(this, BleConnectionService::class.java).setAction(DrivingModeController.ACTION_SET_MODE)
            .putExtra(DrivingModeController.EXTRA_MODE, mode),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun stateIsConnected() = _connectionState.value == BleConnectionState.CONNECTED

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
