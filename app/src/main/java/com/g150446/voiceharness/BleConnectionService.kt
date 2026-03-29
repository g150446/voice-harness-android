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

class BleConnectionService : Service() {

    companion object {
        // Flows live in the companion object — independent of Service lifecycle.
        // ViewModel can safely collect from these before the Service starts.
        private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
        val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

        private val _audioPackets = MutableSharedFlow<AudioPacket>(extraBufferCapacity = 64)
        val audioPackets: SharedFlow<AudioPacket> = _audioPackets.asSharedFlow()

        private val _bleEvents = MutableSharedFlow<BleEvent>(extraBufferCapacity = 16)
        val bleEvents: SharedFlow<BleEvent> = _bleEvents.asSharedFlow()

        private var instance: BleConnectionService? = null

        fun sendCommand(byte: Byte) {
            instance?.bleManager?.sendToRx(byte)
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

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "Service created")

        createNotificationChannel()
        startForegroundWithNotification("BLE: Scanning...")

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bleManager = BleManager(applicationContext, serviceScope).also { mgr ->
            mgr.start(bluetoothManager)

            // Relay BleManager flows → companion object flows (observable by ViewModel)
            serviceScope.launch {
                mgr.connectionState.collect { state ->
                    _connectionState.value = state
                    val text = when (state) {
                        BleConnectionState.SCANNING -> "BLE: Scanning..."
                        BleConnectionState.CONNECTING -> "BLE: Connecting..."
                        BleConnectionState.CONNECTED -> "BLE: Connected"
                        BleConnectionState.DISCONNECTED -> "BLE: Disconnected — reconnecting"
                    }
                    updateNotification(text)
                }
            }
            serviceScope.launch {
                mgr.audioPackets.collect { _audioPackets.tryEmit(it) }
            }
            serviceScope.launch {
                mgr.bleEvents.collect { _bleEvents.tryEmit(it) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        bleManager?.disconnect()
        serviceScope.cancel()
        _connectionState.value = BleConnectionState.DISCONNECTED
        instance = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

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

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
