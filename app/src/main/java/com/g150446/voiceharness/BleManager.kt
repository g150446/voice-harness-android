package com.g150446.voiceharness

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
    data object RecordingStarted : BleEvent()
    data object RecordingStopped : BleEvent()
    data class MotionActive(val ax: Float, val ay: Float, val az: Float) : BleEvent()
    data object GestureDetected : BleEvent()
    data object LightSleepEnter : BleEvent()
    data object LightSleepWake : BleEvent()
    data object PeerConnected : BleEvent()
    data object PeerDisconnected : BleEvent()
}

private enum class ScanPurpose {
    AUTO_CONNECT,
    MANUAL_SCAN,
    MANUAL_CONNECT
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
        val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180F-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL_CHAR_UUID: UUID = UUID.fromString("00002A19-0000-1000-8000-00805f9b34fb")
        const val SCAN_TIMEOUT_MS = 30_000L
        const val DEVICE_NAME = "HarnessNode"
    }

    private val preferences = BleConnectionPreferences(context)

    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BleConnectionState> = _connectionState

    private val _audioPackets = MutableSharedFlow<AudioPacket>(extraBufferCapacity = 64)
    val audioPackets: SharedFlow<AudioPacket> = _audioPackets

    private val _bleEvents = MutableSharedFlow<BleEvent>(extraBufferCapacity = 16)
    val bleEvents: SharedFlow<BleEvent> = _bleEvents

    private val _scannedDevices = MutableStateFlow<List<BleDeviceInfo>>(emptyList())
    val scannedDevices: StateFlow<List<BleDeviceInfo>> = _scannedDevices

    private val _preferredDevice = MutableStateFlow(preferences.preferredDevice())
    val preferredDevice: StateFlow<BleDeviceInfo?> = _preferredDevice

    private val _batteryLevel = MutableStateFlow<Int?>(null)
    val batteryLevel: StateFlow<Int?> = _batteryLevel

    private val _isPrimary = MutableStateFlow(true)
    val isPrimary: StateFlow<Boolean> = _isPrimary

    fun setIsPrimary(value: Boolean) {
        _isPrimary.value = value
    }

    private var bluetoothManager: BluetoothManager? = null
    private var gatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var batteryLevelCharacteristic: BluetoothGattCharacteristic? = null
    private var isScanning = false
    private var lastSeqNum = -1

    private var reconnectJob: Job? = null
    private var scanTimeoutJob: Job? = null
    private var reconnectDelayMs = 2_000L

    private val discoveredDevices = LinkedHashMap<String, BluetoothDevice>()
    private val discoveredDeviceInfo = LinkedHashMap<String, BleDeviceInfo>()
    private var pendingTargetAddress: String? = null
    private var scanPurpose: ScanPurpose? = null
    private var autoReconnectEnabled = preferences.isAutoReconnectEnabled()
    private var isShuttingDown = false

    fun start(bluetoothManager: BluetoothManager) {
        this.bluetoothManager = bluetoothManager
        isShuttingDown = false
        if (autoReconnectEnabled) {
            startAutoConnect()
        } else {
            _connectionState.value = BleConnectionState.DISCONNECTED
        }
    }

    fun startManualScan() {
        reconnectJob?.cancel()
        pendingTargetAddress = null
        startScan(scanPurpose = ScanPurpose.MANUAL_SCAN, targetAddress = null)
    }

    fun connectToDevice(address: String) {
        reconnectJob?.cancel()
        autoReconnectEnabled = true
        pendingTargetAddress = address

        val knownDevice = discoveredDevices[address]
        if (knownDevice != null) {
            bluetoothManager?.let { stopScan(it) }
            connectGatt(knownDevice, ScanPurpose.MANUAL_CONNECT)
            return
        }

        startScan(scanPurpose = ScanPurpose.MANUAL_CONNECT, targetAddress = address)
    }

    fun disconnectManually() {
        Log.d(TAG, "Manual BLE disconnect requested")
        autoReconnectEnabled = false
        preferences.setAutoReconnectEnabled(false)
        pendingTargetAddress = null
        reconnectJob?.cancel()
        bluetoothManager?.let { stopScan(it) }
        clearDiscoveredDevices()
        disconnectInternal()
        _connectionState.value = BleConnectionState.DISCONNECTED
    }

    fun shutdown() {
        isShuttingDown = true
        reconnectJob?.cancel()
        scanTimeoutJob?.cancel()
        bluetoothManager?.let { stopScan(it) }
        disconnectInternal()
        _connectionState.value = BleConnectionState.DISCONNECTED
    }

    private fun startAutoConnect() {
        val preferred = _preferredDevice.value
        if (preferred == null) {
            autoReconnectEnabled = false
            preferences.setAutoReconnectEnabled(false)
            _connectionState.value = BleConnectionState.DISCONNECTED
            return
        }
        pendingTargetAddress = preferred.address
        startScan(scanPurpose = ScanPurpose.AUTO_CONNECT, targetAddress = preferred.address)
    }

    private fun startScan(scanPurpose: ScanPurpose, targetAddress: String?) {
        val bluetoothManager = bluetoothManager ?: run {
            Log.w(TAG, "Bluetooth manager unavailable")
            return
        }
        val adapter = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth not enabled")
            _connectionState.value = BleConnectionState.DISCONNECTED
            return
        }

        reconnectJob?.cancel()
        scanTimeoutJob?.cancel()
        if (isScanning) {
            stopScan(bluetoothManager)
        }

        this.scanPurpose = scanPurpose
        pendingTargetAddress = targetAddress
        if (scanPurpose != ScanPurpose.AUTO_CONNECT) {
            clearDiscoveredDevices()
        }

        val scanner = adapter.bluetoothLeScanner ?: run {
            Log.e(TAG, "BLE scanner unavailable")
            _connectionState.value = BleConnectionState.DISCONNECTED
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        isScanning = true
        _connectionState.value = BleConnectionState.SCANNING
        scanner.startScan(emptyList(), settings, scanCallback)
        Log.d(TAG, "BLE scan started: purpose=$scanPurpose target=${targetAddress ?: "none"}")

        scanTimeoutJob = scope.launch {
            delay(SCAN_TIMEOUT_MS)
            if (!isScanning) return@launch
            Log.d(TAG, "Scan timeout: purpose=$scanPurpose target=${targetAddress ?: "none"}")
            stopScan(bluetoothManager)
            onScanFinishedWithoutConnection(scanPurpose)
        }
    }

    private fun stopScan(bluetoothManager: BluetoothManager) {
        if (!isScanning) return
        isScanning = false
        scanTimeoutJob?.cancel()
        try {
            bluetoothManager.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping scan: ${e.message}")
        }
    }

    private fun onScanFinishedWithoutConnection(purpose: ScanPurpose?) {
        if (purpose == ScanPurpose.AUTO_CONNECT || purpose == ScanPurpose.MANUAL_CONNECT) {
            scheduleReconnectIfAllowed()
        } else {
            _connectionState.value = BleConnectionState.DISCONNECTED
        }
    }

    private fun clearDiscoveredDevices() {
        discoveredDevices.clear()
        discoveredDeviceInfo.clear()
        _scannedDevices.value = emptyList()
    }

    private fun updateScannedDevice(result: ScanResult) {
        val address = result.device.address ?: return
        val name = result.device.name
            ?: result.scanRecord?.deviceName
            ?: discoveredDeviceInfo[address]?.name
            ?: address
        discoveredDevices[address] = result.device
        discoveredDeviceInfo[address] = BleDeviceInfo(
            address = address,
            name = name,
            rssi = result.rssi
        )
        _scannedDevices.value = discoveredDeviceInfo.values
            .sortedWith(
                compareByDescending<BleDeviceInfo> { it.rssi ?: Int.MIN_VALUE }
                    .thenBy { it.name }
            )
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val uuids = result.scanRecord?.serviceUuids
            val deviceName = result.device.name ?: result.scanRecord?.deviceName ?: ""
            val hasServiceUuid = uuids != null && uuids.contains(ParcelUuid(SERVICE_UUID))
            val hasDeviceName = deviceName == DEVICE_NAME

            Log.v(TAG, "Scan result: addr=${result.device.address} name=$deviceName uuid=$hasServiceUuid")

            // Accept if service UUID matches, or if device name matches (fallback for
            // background scans where scan responses may not be received).
            if (!hasServiceUuid && !hasDeviceName) return

            Log.d(TAG, "HarnessNode found: addr=${result.device.address} name=$deviceName uuid=$hasServiceUuid")
            updateScannedDevice(result)
            val targetAddress = pendingTargetAddress
            if (targetAddress != null && result.device.address == targetAddress) {
                scanTimeoutJob?.cancel()
                bluetoothManager?.let { stopScan(it) }
                connectGatt(result.device, scanPurpose ?: ScanPurpose.MANUAL_CONNECT)
            } else if (targetAddress != null) {
                Log.w(TAG, "Address mismatch: expected=$targetAddress found=${result.device.address}")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            isScanning = false
            _connectionState.value = BleConnectionState.DISCONNECTED
            onScanFinishedWithoutConnection(scanPurpose)
        }
    }

    private fun connectGatt(device: BluetoothDevice, purpose: ScanPurpose) {
        disconnectInternal(closeOnly = true)
        scanPurpose = purpose
        pendingTargetAddress = device.address
        _connectionState.value = BleConnectionState.CONNECTING
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        Log.d(TAG, "Connecting to ${device.address} (purpose=$purpose)")
    }

    private fun disconnectInternal(closeOnly: Boolean = false) {
        scanTimeoutJob?.cancel()
        reconnectJob?.cancel()
        val currentGatt = gatt
        gatt = null
        rxCharacteristic = null
        batteryLevelCharacteristic = null
        _batteryLevel.value = null
        lastSeqNum = -1
        if (currentGatt != null) {
            try {
                if (!closeOnly) {
                    currentGatt.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error disconnecting GATT: ${e.message}")
            }
            try {
                currentGatt.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing GATT: ${e.message}")
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val isCurrentGatt = this@BleManager.gatt === gatt
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (!isCurrentGatt) return
                    Log.d(TAG, "GATT connected, requesting MTU")
                    gatt.requestMtu(247)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "GATT disconnected (status=$status)")
                    gatt.close()
                    if (!isCurrentGatt) return
                    this@BleManager.gatt = null
                    rxCharacteristic = null
                    lastSeqNum = -1
                    _connectionState.value = BleConnectionState.DISCONNECTED
                    if (!isShuttingDown) {
                        scheduleReconnectIfAllowed()
                    }
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
                gatt.disconnect()
                return
            }

            val service = gatt.getService(SERVICE_UUID) ?: run {
                Log.e(TAG, "Audio service not found")
                gatt.disconnect()
                return
            }

            val txChar = service.getCharacteristic(TX_CHAR_UUID) ?: run {
                Log.e(TAG, "TX characteristic not found")
                gatt.disconnect()
                return
            }
            rxCharacteristic = service.getCharacteristic(RX_CHAR_UUID)
            batteryLevelCharacteristic = gatt.getService(BATTERY_SERVICE_UUID)
                ?.getCharacteristic(BATTERY_LEVEL_CHAR_UUID)

            gatt.setCharacteristicNotification(txChar, true)

            val cccd = txChar.getDescriptor(CCCD_UUID) ?: run {
                Log.e(TAG, "CCCD descriptor not found")
                gatt.disconnect()
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
            if (descriptor.uuid != CCCD_UUID || status != BluetoothGatt.GATT_SUCCESS) return

            when (descriptor.characteristic.uuid) {
                TX_CHAR_UUID -> {
                    Log.d(TAG, "TX notifications enabled — BLE fully connected")
                    reconnectJob?.cancel()
                    reconnectDelayMs = 2_000L
                    _connectionState.value = BleConnectionState.CONNECTED

                    val connectedDevice = BleDeviceInfo(
                        address = gatt.device.address,
                        name = gatt.device.name ?: _preferredDevice.value?.name ?: gatt.device.address
                    )
                    if (scanPurpose == ScanPurpose.MANUAL_CONNECT) {
                        autoReconnectEnabled = true
                        preferences.savePreferredDevice(connectedDevice, autoReconnectEnabled = true)
                        _preferredDevice.value = connectedDevice
                    } else if (scanPurpose == ScanPurpose.AUTO_CONNECT) {
                        preferences.savePreferredDevice(connectedDevice, autoReconnectEnabled = true)
                        _preferredDevice.value = connectedDevice
                    }
                    pendingTargetAddress = connectedDevice.address

                    // Enable battery level notifications (serialized after TX CCCD)
                    batteryLevelCharacteristic?.let { char ->
                        gatt.setCharacteristicNotification(char, true)
                        val cccd = char.getDescriptor(CCCD_UUID) ?: return@let
                        if (Build.VERSION.SDK_INT >= 33) {
                            gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        } else {
                            @Suppress("DEPRECATION")
                            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            gatt.writeDescriptor(cccd)
                        }
                    }
                }

                BATTERY_LEVEL_CHAR_UUID -> {
                    Log.d(TAG, "Battery notifications enabled — reading initial level")
                    batteryLevelCharacteristic?.let { gatt.readCharacteristic(it) }
                    // Role claim (0x02) is sent in onCharacteristicRead after the read completes,
                    // to avoid a GATT race between readCharacteristic and writeCharacteristic.
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicData(characteristic.uuid, value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            handleCharacteristicData(characteristic.uuid, characteristic.value ?: return)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (characteristic.uuid == BATTERY_LEVEL_CHAR_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                if (value.isNotEmpty()) {
                    val level = (value[0].toInt() and 0xFF).coerceIn(0, 100)
                    Log.d(TAG, "Battery level: $level%")
                    _batteryLevel.value = level
                }
                // Only claim primary when the preference is ANDROID.
                // In MAC_HANDY mode we must NOT send 0x02 here, because the retry
                // sends (300 ms / 600 ms) would race against and override the 0x03
                // yield that the 0x31 handler issues when Handy connects.
                val priority = preferences.connectionPriority()
                if (priority != ConnectionPriority.MAC_HANDY) {
                    sendToRxWithRetry(0x02.toByte())
                    _isPrimary.value = true
                    Log.d(TAG, "Role declared: primary (preference=$priority)")
                } else {
                    Log.d(TAG, "Role: not claiming primary (preference=MAC_HANDY)")
                }
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid == BATTERY_LEVEL_CHAR_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                val value = characteristic.value ?: return
                if (value.isNotEmpty()) {
                    val level = (value[0].toInt() and 0xFF).coerceIn(0, 100)
                    Log.d(TAG, "Battery level: $level%")
                    _batteryLevel.value = level
                }
                // Only claim primary when the preference is ANDROID (see sibling override above).
                val priority = preferences.connectionPriority()
                if (priority != ConnectionPriority.MAC_HANDY) {
                    sendToRxWithRetry(0x02.toByte())
                    _isPrimary.value = true
                    Log.d(TAG, "Role declared: primary (preference=$priority)")
                } else {
                    Log.d(TAG, "Role: not claiming primary (preference=MAC_HANDY)")
                }
            }
        }
    }

    private fun handleCharacteristicData(uuid: UUID, data: ByteArray) {
        if (uuid == BATTERY_LEVEL_CHAR_UUID) {
            if (data.isNotEmpty()) {
                val level = (data[0].toInt() and 0xFF).coerceIn(0, 100)
                Log.d(TAG, "Battery level notification: $level%")
                _batteryLevel.value = level
            }
            return
        }

        if (uuid != TX_CHAR_UUID || data.size < 2) return

        when (data[1].toInt() and 0xFF) {
            0xAA -> {
                val seqNum = data[0].toInt() and 0xFF
                if (lastSeqNum >= 0) {
                    val expected = (lastSeqNum + 1) and 0xFF
                    if (seqNum != expected && seqNum != lastSeqNum) {
                        val dropped = (seqNum - expected + 256) and 0xFF
                        Log.w(TAG, "PCM gap: ~$dropped packets dropped (expected $expected, got $seqNum)")
                    }
                }
                lastSeqNum = seqNum
                _audioPackets.tryEmit(AudioPacket(seqNum, data.copyOfRange(2, data.size)))
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
                    0x31 -> {
                        // peer connected: negotiate role based on preference
                        val priority = preferences.connectionPriority()
                        if (priority == ConnectionPriority.MAC_HANDY) {
                            sendToRxWithRetry(0x03.toByte()) // yield to Mac Handy
                            _isPrimary.value = false
                        }
                        BleEvent.PeerConnected
                    }
                    0x32 -> {
                        _isPrimary.value = true
                        BleEvent.PeerDisconnected
                    }
                    else -> null
                } ?: return
                _bleEvents.tryEmit(event)
            }
        }
    }

    private fun parseMotionActive(data: ByteArray): BleEvent.MotionActive {
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

    fun sendToRxWithRetry(byte: Byte, retries: Int = 2, delayMs: Long = 300) {
        sendToRx(byte)
        val handler = Handler(Looper.getMainLooper())
        for (i in 1..retries) {
            handler.postDelayed({ sendToRx(byte) }, delayMs * i)
        }
    }

    private fun scheduleReconnectIfAllowed() {
        if (!autoReconnectEnabled) {
            Log.d(TAG, "Auto reconnect disabled; staying disconnected")
            return
        }
        val bluetoothManager = bluetoothManager ?: return
        val targetAddress = pendingTargetAddress ?: _preferredDevice.value?.address
        if (targetAddress.isNullOrBlank()) {
            Log.d(TAG, "No preferred device to reconnect")
            return
        }

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            Log.d(TAG, "Reconnecting in ${reconnectDelayMs}ms to $targetAddress")
            delay(reconnectDelayMs)
            reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(60_000L)
            if (!isShuttingDown) {
                startScan(
                    scanPurpose = ScanPurpose.AUTO_CONNECT,
                    targetAddress = targetAddress
                )
            }
        }
    }
}
