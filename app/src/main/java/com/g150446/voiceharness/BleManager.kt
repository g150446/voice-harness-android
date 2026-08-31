package com.g150446.voiceharness

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "BleManager"

/** Retry gap when the stack refuses a write outright (usually a busy GATT). */
private const val RX_WRITE_RETRY_MS = 150L

/** Guard against stacks that never call onCharacteristicWrite. */
private const val RX_WRITE_TIMEOUT_MS = 2_000L

enum class BleConnectionState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

data class AudioPacket(val seqNum: Int, val pcmData: ByteArray)

sealed class BleEvent {
    data object RecordingStarted : BleEvent()
    data object RecordingStopped : BleEvent()
    data class MotionActive(val ax: Float, val ay: Float, val az: Float) : BleEvent()
    data object GestureDetected : BleEvent()
    data object DoubleTap : BleEvent()
    data object SingleTap : BleEvent()
    data object LightSleepEnter : BleEvent()
    data object LightSleepWake : BleEvent()
    data object PeerConnected : BleEvent()
    data object PeerDisconnected : BleEvent()
    /**
     * Operation mode ack (event 0x40). [effective] is what the node is running now;
     * [pending] is a mode deferred until the current recording ends, or
     * [OPERATION_MODE_PENDING_NONE] when nothing is deferred.
     */
    data class OperationModeAck(val effective: Int, val pending: Int) : BleEvent()

    /**
     * Gesture capture switch ack (event 0x39). The node keeps this in RAM, so a
     * node reset silently turns it off and the app must not assume its last
     * request still holds.
     */
    data class GestureCaptureAck(val enabled: Boolean) : BleEvent()
    /** Live milestone/reject sample (event 0x30). Not used for voice pipeline. */
    data class GestureDiag(
        val stage: Int,
        val reason: Int,
        val v1: Float,
        val v2: Float,
        val v3: Float,
    ) : BleEvent()
}

/** Parses event packets whose decoding has no connection-management side effects. */
internal fun parseSimpleBleEvent(data: ByteArray): BleEvent? {
    if (data.size < 3 || (data[1].toInt() and 0xFF) != 0x55) return null

    return when (data[2].toInt() and 0xFF) {
        0x02 -> BleEvent.RecordingStopped
        0x11 -> BleEvent.GestureDetected
        0x12 -> BleEvent.DoubleTap
        0x14 -> BleEvent.SingleTap
        0x20 -> BleEvent.LightSleepEnter
        0x21 -> BleEvent.LightSleepWake
        else -> null
    }
}

/** Node value for "no mode change is deferred" in an [BleEvent.OperationModeAck]. */
const val OPERATION_MODE_PENDING_NONE = 0xFF

/** Layout version carried by trajectory_begin (0x36); bump with the packing. */
internal const val TRAJECTORY_VERSION = 1

/** [u16 t_ms][flags][f32 ax ay az gx gy gz] per sample in a 0x37 chunk. */
internal const val TRAJECTORY_SAMPLE_BYTES = 27

/** Parses the gesture capture ack (event 0x39, 4 bytes fixed). */
internal fun parseGestureCaptureAck(data: ByteArray): BleEvent.GestureCaptureAck? {
    if (data.size < 4 || (data[1].toInt() and 0xFF) != 0x55) return null
    if ((data[2].toInt() and 0xFF) != 0x39) return null
    return BleEvent.GestureCaptureAck(enabled = data[3].toInt() != 0)
}

/**
 * Parses the operation mode ack (event 0x40, 5 bytes fixed). Kept out of
 * [parseSimpleBleEvent], which only handles payload-free events.
 */
internal fun parseOperationModeAck(data: ByteArray): BleEvent.OperationModeAck? {
    if (data.size < 5 || (data[1].toInt() and 0xFF) != 0x55) return null
    if ((data[2].toInt() and 0xFF) != 0x40) return null
    return BleEvent.OperationModeAck(
        effective = data[3].toInt() and 0xFF,
        pending = data[4].toInt() and 0xFF,
    )
}

sealed class BleVoiceInput {
    data class Audio(val packet: AudioPacket) : BleVoiceInput()
    data class Event(val event: BleEvent) : BleVoiceInput()
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

    // Audio packets and recording events must share one lossless queue. Separate flows can
    // let RecordingStopped overtake buffered PCM and tryEmit silently fails when full.
    private val voiceInputChannel = Channel<BleVoiceInput>(Channel.UNLIMITED)
    val voiceInputs = voiceInputChannel.receiveAsFlow()

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
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rxQueue = ArrayDeque<ByteArray>()
    private var rxWriteInFlight = false
    private val rxWriteTimeout = Runnable {
        Log.w(TAG, "RX write timed out; resuming the queue")
        rxWriteInFlight = false
        pumpRxQueue()
    }
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
        voiceInputChannel.close()
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
        clearRxQueue()
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
                    requestAudioConnectionPriority(gatt, "connected")
                    Log.d(TAG, "GATT connected, requesting MTU")
                    gatt.requestMtu(247)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "GATT disconnected (status=$status)")
                    gatt.close()
                    if (!isCurrentGatt) return
                    this@BleManager.gatt = null
                    rxCharacteristic = null
                    // Commands aimed at the old link must not ride the new one.
                    clearRxQueue()
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

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (characteristic.uuid != RX_CHAR_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "RX write failed: status=$status")
            }
            onRxWriteSettled()
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
                enqueueVoiceInput(
                    BleVoiceInput.Audio(
                        AudioPacket(seqNum, data.copyOfRange(2, data.size))
                    )
                )
            }

            0x55 -> {
                if (data.size < 3) return
                val eventCode = data[2].toInt() and 0xFF
                if (eventCode != 0x10 && eventCode != 0x30 && eventCode != 0x34 &&
                    eventCode != 0x37
                ) {
                    // Skip high-rate motion/diag samples; log control/tap events.
                    Log.i(
                        TAG,
                        "TX event 0x%02X (%s)".format(
                            eventCode,
                            data.take(minOf(data.size, 8)).joinToString(" ") {
                                "%02X".format(it.toInt() and 0xFF)
                            },
                        ),
                    )
                }
                when (val code = eventCode) {
                    0x30 -> {
                        // [00 55 30 stage reason f32×3] = 17 bytes
                        if (data.size >= 17) {
                            val stage = data[3].toInt() and 0xFF
                            val reason = data[4].toInt() and 0xFF
                            val v1 = GestureDiagStore.parseFloatLe(data, 5)
                            val v2 = GestureDiagStore.parseFloatLe(data, 9)
                            val v3 = GestureDiagStore.parseFloatLe(data, 13)
                            GestureDiagStore.onLiveDiag(stage, reason, v1, v2, v3)
                        }
                        return
                    }
                    0x33 -> {
                        // history_begin: [count][session]
                        if (data.size >= 5) {
                            val count = data[3].toInt() and 0xFF
                            val session = data[4].toInt() and 0xFF
                            GestureDiagStore.onHistoryBegin(count, session)
                        }
                        return
                    }
                    0x34 -> {
                        // history_entry: [u16 t_ms][stage][reason][f32×3] = 19
                        if (data.size >= 19) {
                            val tMs = GestureDiagStore.parseU16Le(data, 3)
                            val stage = data[5].toInt() and 0xFF
                            val reason = data[6].toInt() and 0xFF
                            val v1 = GestureDiagStore.parseFloatLe(data, 7)
                            val v2 = GestureDiagStore.parseFloatLe(data, 11)
                            val v3 = GestureDiagStore.parseFloatLe(data, 15)
                            GestureDiagStore.onHistoryEntry(tMs, stage, reason, v1, v2, v3)
                        }
                        return
                    }
                    0x35 -> {
                        if (data.size >= 5) {
                            val count = data[3].toInt() and 0xFF
                            val session = data[4].toInt() and 0xFF
                            GestureDiagStore.onHistoryEnd(count, session)
                        }
                        return
                    }
                    0x36 -> {
                        // trajectory_begin: [ver][session][result][reason]
                        //                   [u16 count][u16 period_ms][f32 gyro_bias_y] = 15
                        if (data.size >= 15) {
                            val version = data[3].toInt() and 0xFF
                            if (version != TRAJECTORY_VERSION) {
                                Log.w(TAG, "Unknown trajectory version $version; ignoring batch")
                                return
                            }
                            GestureTrajectoryStore.onBegin(
                                session = data[4].toInt() and 0xFF,
                                result = data[5].toInt() and 0xFF,
                                reason = data[6].toInt() and 0xFF,
                                sampleCount = GestureDiagStore.parseU16Le(data, 7),
                                periodMs = GestureDiagStore.parseU16Le(data, 9),
                                gyroBiasY = GestureDiagStore.parseFloatLe(data, 11),
                            )
                        }
                        return
                    }
                    0x37 -> {
                        // trajectory_chunk: [session][u16 start][count] then
                        // count × ([u16 t_ms][flags][f32 ax ay az gx gy gz]) = 27 B
                        if (data.size >= 7) {
                            val start = GestureDiagStore.parseU16Le(data, 4)
                            val count = data[6].toInt() and 0xFF
                            val samples = ArrayList<GestureTrajectorySample>(count)
                            for (i in 0 until count) {
                                val o = 7 + i * TRAJECTORY_SAMPLE_BYTES
                                if (o + TRAJECTORY_SAMPLE_BYTES > data.size) break
                                samples += GestureTrajectorySample(
                                    tMs = GestureDiagStore.parseU16Le(data, o),
                                    flags = data[o + 2].toInt() and 0xFF,
                                    ax = GestureDiagStore.parseFloatLe(data, o + 3),
                                    ay = GestureDiagStore.parseFloatLe(data, o + 7),
                                    az = GestureDiagStore.parseFloatLe(data, o + 11),
                                    gx = GestureDiagStore.parseFloatLe(data, o + 15),
                                    gy = GestureDiagStore.parseFloatLe(data, o + 19),
                                    gz = GestureDiagStore.parseFloatLe(data, o + 23),
                                )
                            }
                            GestureTrajectoryStore.onChunk(start, samples)
                        }
                        return
                    }
                    0x38 -> {
                        // trajectory_end: [session][u16 sent][flags]
                        if (data.size >= 7) {
                            GestureTrajectoryStore.onEnd(
                                sentCount = GestureDiagStore.parseU16Le(data, 4),
                                flags = data[6].toInt() and 0xFF,
                            )
                        }
                        return
                    }
                }
                val event = when (data[2].toInt() and 0xFF) {
                    0x01 -> {
                        // Avoid false PCM gap warnings across recording sessions.
                        lastSeqNum = -1
                        this@BleManager.gatt?.let {
                            requestAudioConnectionPriority(it, "recording started")
                        }
                        BleEvent.RecordingStarted
                    }
                    0x02 -> parseSimpleBleEvent(data)
                    0x10 -> parseMotionActive(data)
                    0x11 -> {
                        Log.d(TAG, "Gesture detected (motion settled)")
                        parseSimpleBleEvent(data)
                    }
                    0x12 -> {
                        Log.i(TAG, "Double tap event received")
                        parseSimpleBleEvent(data)
                    }
                    0x14 -> {
                        Log.i(TAG, "Single tap event received")
                        parseSimpleBleEvent(data)
                    }
                    0x20 -> parseSimpleBleEvent(data)
                    0x21 -> parseSimpleBleEvent(data)
                    0x31 -> {
                        // peer connected: negotiate role based on preference
                        val priority = preferences.connectionPriority()
                        if (priority == ConnectionPriority.MAC_HANDY) {
                            sendToRxWithRetry(0x03.toByte()) // yield to Mac Handy
                            _isPrimary.value = false
                            Log.i(TAG, "Peer connected — yielded primary (MAC_HANDY)")
                        }
                        BleEvent.PeerConnected
                    }
                    0x32 -> {
                        // Peer left: reclaim primary so FW keeps delivering events/audio here.
                        _isPrimary.value = true
                        sendToRxWithRetry(0x02.toByte())
                        Log.i(TAG, "Peer disconnected — reclaimed primary")
                        BleEvent.PeerDisconnected
                    }
                    0x39 -> parseGestureCaptureAck(data)
                    0x40 -> parseOperationModeAck(data)
                    else -> null
                } ?: return
                enqueueVoiceInput(BleVoiceInput.Event(event))
            }
        }
    }

    private fun enqueueVoiceInput(input: BleVoiceInput) {
        val result = voiceInputChannel.trySend(input)
        if (result.isFailure) {
            Log.e(TAG, "Failed to enqueue BLE voice input: $input", result.exceptionOrNull())
        }
    }

    private fun requestAudioConnectionPriority(gatt: BluetoothGatt, reason: String) {
        val accepted = gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        if (accepted) {
            Log.d(TAG, "Requested high BLE connection priority ($reason)")
        } else {
            Log.w(TAG, "High BLE connection priority request rejected ($reason)")
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

    fun sendToRx(byte: Byte) = sendToRx(byteArrayOf(byte))

    /**
     * Queues one RX command.
     *
     * Android carries a single outstanding GATT operation per connection, so two
     * writes issued in the same tick lose the second one silently — no exception,
     * no callback, nothing on the air. That is what dropped the capture switch
     * when the CONNECTED handler sent the driving mode and the capture flag
     * back to back. Serialise them instead of racing.
     */
    fun sendToRx(bytes: ByteArray) {
        mainHandler.post {
            rxQueue.addLast(bytes)
            pumpRxQueue()
        }
    }

    /** Main thread only. */
    private fun pumpRxQueue() {
        if (rxWriteInFlight) return
        val bytes = rxQueue.removeFirstOrNull() ?: return
        val characteristic = rxCharacteristic ?: run {
            Log.w(TAG, "RX characteristic not available; dropping ${bytes.size} bytes")
            rxQueue.clear()
            return
        }
        val started = if (Build.VERSION.SDK_INT >= 33) {
            gatt?.writeCharacteristic(
                characteristic,
                bytes,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = bytes
            @Suppress("DEPRECATION")
            gatt?.writeCharacteristic(characteristic) == true
        }
        val hex = bytes.joinToString(" ") { "%02x".format(it) }
        if (!started) {
            // Say so rather than letting the caller believe the node was told.
            Log.w(TAG, "RX write rejected by the stack: $hex")
            mainHandler.postDelayed({ pumpRxQueue() }, RX_WRITE_RETRY_MS)
            return
        }
        rxWriteInFlight = true
        Log.d(TAG, "Sent to RX: $hex")
        // Some stacks never deliver onCharacteristicWrite; without this the queue
        // would stall for the life of the connection.
        mainHandler.postDelayed(rxWriteTimeout, RX_WRITE_TIMEOUT_MS)
    }

    /** Main thread only. */
    private fun onRxWriteSettled() {
        mainHandler.removeCallbacks(rxWriteTimeout)
        rxWriteInFlight = false
        pumpRxQueue()
    }

    private fun clearRxQueue() {
        mainHandler.post {
            mainHandler.removeCallbacks(rxWriteTimeout)
            rxQueue.clear()
            rxWriteInFlight = false
        }
    }

    /**
     * Repeats a one-byte command.
     *
     * Predates the RX queue, and was how the role-negotiation commands worked
     * around writes being dropped when issued back to back. The queue now
     * delivers every write, so this is belt-and-braces for a command the node
     * treats as idempotent; new callers should use [sendToRx].
     */
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
