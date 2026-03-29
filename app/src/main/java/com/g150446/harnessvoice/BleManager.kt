package com.g150446.harnessvoice

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "BleManager"

enum class BleConnectionState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

data class AudioPacket(val seqNum: Int, val pcmData: ByteArray)

sealed class BleEvent {
    object RecordingStarted : BleEvent()
    object RecordingStopped : BleEvent()
    data class MotionActive(val ax: Float, val ay: Float, val az: Float) : BleEvent()
    object GestureDetected : BleEvent()
    object LightSleepEnter : BleEvent()
    object LightSleepWake : BleEvent()
}

@SuppressLint("MissingPermission")
class BleManager(
    private val context: Context,
    private val scope: CoroutineScope
) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("00000001-0000-1000-8000-00805f9b34fb")
        val TX_CHAR_UUID: UUID = UUID.fromString("00000002-0000-1000-8000-00805f9b34fb")
        val RX_CHAR_UUID: UUID = UUID.fromString("00000003-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val SCAN_TIMEOUT_MS = 30_000L
    }

    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BleConnectionState> = _connectionState

    private val _audioPackets = MutableSharedFlow<AudioPacket>(extraBufferCapacity = 64)
    val audioPackets: SharedFlow<AudioPacket> = _audioPackets

    private val _bleEvents = MutableSharedFlow<BleEvent>(extraBufferCapacity = 16)
    val bleEvents: SharedFlow<BleEvent> = _bleEvents

    private var gatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var isScanning = false
    private var lastSeqNum = -1

    private var reconnectJob: Job? = null
    private var scanTimeoutJob: Job? = null
    private var reconnectDelayMs = 2000L

    // --- Scan ---

    fun startScan(bluetoothManager: BluetoothManager) {
        val adapter = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth not enabled")
            return
        }
        if (isScanning) return
        isScanning = true
        _connectionState.value = BleConnectionState.SCANNING

        val scanner = adapter.bluetoothLeScanner ?: run {
            Log.e(TAG, "BLE scanner unavailable")
            _connectionState.value = BleConnectionState.DISCONNECTED
            isScanning = false
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // No ScanFilter: some firmware puts service UUID only in scan response,
        // which Android's ScanFilter ignores. We match manually in onScanResult
        // using scanRecord.serviceUuids which covers both AD and scan response.
        scanner.startScan(emptyList(), settings, scanCallback)
        Log.d(TAG, "BLE scan started (no filter)")

        // Stop scan after timeout to avoid draining battery indefinitely
        scanTimeoutJob?.cancel()
        scanTimeoutJob = scope.launch {
            delay(SCAN_TIMEOUT_MS)
            if (isScanning) {
                Log.d(TAG, "Scan timeout — scheduling retry")
                stopScan(bluetoothManager)
                scheduleReconnect()
            }
        }
    }

    private fun stopScan(bluetoothManager: BluetoothManager) {
        if (!isScanning) return
        isScanning = false
        try {
            bluetoothManager.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping scan: ${e.message}")
        }
    }

    private var bluetoothManager: BluetoothManager? = null

    fun start(bluetoothManager: BluetoothManager) {
        this.bluetoothManager = bluetoothManager
        startScan(bluetoothManager)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // Match by service UUID in scanRecord (covers both AD and scan response)
            val uuids = result.scanRecord?.serviceUuids
            if (uuids == null || !uuids.contains(ParcelUuid(SERVICE_UUID))) return
            Log.d(TAG, "Found target device: ${result.device.address}")
            scanTimeoutJob?.cancel()
            bluetoothManager?.let { stopScan(it) }
            connectToDevice(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            isScanning = false
            _connectionState.value = BleConnectionState.DISCONNECTED
            scheduleReconnect()
        }
    }

    // --- Connect ---

    private fun connectToDevice(device: BluetoothDevice) {
        _connectionState.value = BleConnectionState.CONNECTING
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        Log.d(TAG, "Connecting to ${device.address}")
    }

    fun disconnect() {
        reconnectJob?.cancel()
        scanTimeoutJob?.cancel()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        rxCharacteristic = null
        isScanning = false
        lastSeqNum = -1
        _connectionState.value = BleConnectionState.DISCONNECTED
        Log.d(TAG, "Disconnected")
    }

    // --- GATT Callback ---

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "GATT connected, requesting MTU")
                    gatt.requestMtu(247)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "GATT disconnected (status=$status)")
                    gatt.close()
                    this@BleManager.gatt = null
                    rxCharacteristic = null
                    _connectionState.value = BleConnectionState.DISCONNECTED
                    scheduleReconnect()
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU changed to $mtu (status=$status)")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                scheduleReconnect()
                return
            }

            val service = gatt.getService(SERVICE_UUID) ?: run {
                Log.e(TAG, "Audio service not found")
                scheduleReconnect()
                return
            }

            val txChar = service.getCharacteristic(TX_CHAR_UUID) ?: run {
                Log.e(TAG, "TX characteristic not found")
                return
            }
            rxCharacteristic = service.getCharacteristic(RX_CHAR_UUID)

            gatt.setCharacteristicNotification(txChar, true)

            val cccd = txChar.getDescriptor(CCCD_UUID) ?: run {
                Log.e(TAG, "CCCD descriptor not found")
                return
            }

            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(cccd)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid == CCCD_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Notifications enabled — BLE fully connected")
                reconnectJob?.cancel()
                reconnectDelayMs = 2000L
                _connectionState.value = BleConnectionState.CONNECTED
            }
        }

        // API 33+ override
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicData(characteristic.uuid, value)
        }

        // API < 33 override
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            handleCharacteristicData(characteristic.uuid, characteristic.value ?: return)
        }
    }

    private fun handleCharacteristicData(uuid: UUID, data: ByteArray) {
        if (uuid != TX_CHAR_UUID || data.size < 2) return

        when (data[1].toInt() and 0xFF) {
            0xAA -> {
                val seqNum = data[0].toInt() and 0xFF
                if (lastSeqNum >= 0) {
                    val expected = (lastSeqNum + 1) and 0xFF
                    if (seqNum != expected) {
                        val dropped = (seqNum - expected + 256) and 0xFF
                        Log.w(TAG, "PCM gap: ~$dropped packets dropped (expected $expected, got $seqNum)")
                    }
                }
                lastSeqNum = seqNum
                val pcm = data.copyOfRange(2, data.size)
                _audioPackets.tryEmit(AudioPacket(seqNum, pcm))
            }
            0x55 -> {
                if (data.size < 3) return
                val event = when (data[2].toInt() and 0xFF) {
                    0x01 -> BleEvent.RecordingStarted
                    0x02 -> BleEvent.RecordingStopped
                    0x10 -> parseMotionActive(data)
                    0x11 -> {
                        Log.d(TAG, "Gesture detected (motion settled)")
                        BleEvent.GestureDetected
                    }
                    0x20 -> BleEvent.LightSleepEnter
                    0x21 -> BleEvent.LightSleepWake
                    else -> null
                } ?: return
                _bleEvents.tryEmit(event)
            }
        }
    }

    private fun parseMotionActive(data: ByteArray): BleEvent.MotionActive {
        // Bytes 3-14: 3 × float32 (little-endian)
        fun floatAt(offset: Int): Float {
            if (data.size < offset + 4) return 0f
            val bits = (data[offset].toInt() and 0xFF) or
                    ((data[offset + 1].toInt() and 0xFF) shl 8) or
                    ((data[offset + 2].toInt() and 0xFF) shl 16) or
                    ((data[offset + 3].toInt() and 0xFF) shl 24)
            return java.lang.Float.intBitsToFloat(bits)
        }
        return BleEvent.MotionActive(floatAt(3), floatAt(7), floatAt(11))
    }

    // --- Send command to nRF52840 ---

    fun sendToRx(byte: Byte) {
        val characteristic = rxCharacteristic ?: run {
            Log.w(TAG, "RX characteristic not available")
            return
        }
        Handler(Looper.getMainLooper()).post {
            if (Build.VERSION.SDK_INT >= 33) {
                gatt?.writeCharacteristic(
                    characteristic,
                    byteArrayOf(byte),
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = byteArrayOf(byte)
                @Suppress("DEPRECATION")
                gatt?.writeCharacteristic(characteristic)
            }
            Log.d(TAG, "Sent to RX: 0x${byte.toInt().and(0xFF).toString(16)}")
        }
    }

    // --- Reconnect ---

    private fun scheduleReconnect() {
        val bm = bluetoothManager ?: return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            Log.d(TAG, "Reconnecting in ${reconnectDelayMs}ms")
            delay(reconnectDelayMs)
            reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(60_000L)
            startScan(bm)
        }
    }
}
