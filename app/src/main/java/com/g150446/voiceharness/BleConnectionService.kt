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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "BleConnectionService"
private const val NOTIFICATION_ID = 1001
private const val CHANNEL_ID = "ble_connection"
private const val WAKE_LOCK_TAG = "HarnessVoice:BleConnectionWakeLock"

class BleConnectionService : Service() {

    companion object {
        // BLE state flows — independent of Service lifecycle.
        private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
        val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

        private val _audioPackets = MutableSharedFlow<AudioPacket>(extraBufferCapacity = 64)
        val audioPackets: SharedFlow<AudioPacket> = _audioPackets.asSharedFlow()

        private val _bleEvents = MutableSharedFlow<BleEvent>(extraBufferCapacity = 16)
        val bleEvents: SharedFlow<BleEvent> = _bleEvents.asSharedFlow()

        private val _scannedDevices = MutableStateFlow<List<BleDeviceInfo>>(emptyList())
        val scannedDevices: StateFlow<List<BleDeviceInfo>> = _scannedDevices.asStateFlow()

        private val _preferredDevice = MutableStateFlow<BleDeviceInfo?>(null)
        val preferredDevice: StateFlow<BleDeviceInfo?> = _preferredDevice.asStateFlow()

        private val _batteryLevel = MutableStateFlow<Int?>(null)
        val batteryLevel: StateFlow<Int?> = _batteryLevel.asStateFlow()

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

        // Internal setters used by VoiceProcessor (same module/package).
        internal fun setVoiceState(state: VoiceState) { _voiceState.value = state }
        internal fun setTranscription(text: String) { _transcription.value = text }
        internal fun setResponse(text: String) { _response.value = text }
        internal fun setErrorMessage(text: String) { _errorMessage.value = text }
        internal fun setBleMode(mode: Boolean) { _bleMode.value = mode }

        private var instance: BleConnectionService? = null

        fun sendCommand(byte: Byte) {
            instance?.bleManager?.sendToRx(byte)
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

        fun disconnectProcessor() {
            instance?.voiceProcessor?.disconnect()
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

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "Service created")

        createNotificationChannel()
        startForegroundWithNotification("BLE: Scanning...")

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bleManager = BleManager(applicationContext, serviceScope).also { mgr ->
            mgr.start(bluetoothManager)

            serviceScope.launch {
                mgr.connectionState.collect { state ->
                    _connectionState.value = state
                    updateNotification(state, _batteryLevel.value)
                    when (state) {
                        BleConnectionState.CONNECTED -> acquireWakeLock()
                        BleConnectionState.DISCONNECTED -> releaseWakeLock()
                        else -> {}
                    }
                }
            }
            serviceScope.launch {
                mgr.audioPackets.collect { _audioPackets.tryEmit(it) }
            }
            serviceScope.launch {
                mgr.bleEvents.collect { _bleEvents.tryEmit(it) }
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
                    updateNotification(_connectionState.value, level)
                }
            }
        }

        voiceProcessor = VoiceProcessor(applicationContext, serviceScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        voiceProcessor?.shutdown()
        bleManager?.shutdown()
        serviceScope.cancel()
        releaseWakeLock()
        _connectionState.value = BleConnectionState.DISCONNECTED
        _scannedDevices.value = emptyList()
        _batteryLevel.value = null
        _voiceState.value = VoiceState.READY
        _bleMode.value = false
        instance = null
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
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(state: BleConnectionState, batteryLevel: Int?) {
        val text = when (state) {
            BleConnectionState.SCANNING -> "BLE: Scanning..."
            BleConnectionState.CONNECTING -> "BLE: Connecting..."
            BleConnectionState.CONNECTED -> batteryLevel?.let { "BLE: Connected  Battery: $it%" } ?: "BLE: Connected"
            BleConnectionState.DISCONNECTED -> "BLE: Disconnected"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
