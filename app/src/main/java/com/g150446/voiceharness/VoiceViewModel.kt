package com.g150446.voiceharness

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class VoiceViewModel(application: Application) : AndroidViewModel(application) {

    private val _bleConnectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val bleConnectionState: StateFlow<BleConnectionState> = _bleConnectionState

    private val _availableBleDevices = MutableStateFlow<List<BleDeviceInfo>>(emptyList())
    val availableBleDevices: StateFlow<List<BleDeviceInfo>> = _availableBleDevices

    private val _preferredBleDevice = MutableStateFlow<BleDeviceInfo?>(null)
    val preferredBleDevice: StateFlow<BleDeviceInfo?> = _preferredBleDevice

    private val _selectedBleDeviceAddress = MutableStateFlow<String?>(null)
    val selectedBleDeviceAddress: StateFlow<String?> = _selectedBleDeviceAddress

    private val _batteryLevel = MutableStateFlow<Int?>(null)
    val batteryLevel: StateFlow<Int?> = _batteryLevel

    // Voice processing state — sourced from BleConnectionService companion (lives in service scope).
    val state: StateFlow<VoiceState> = BleConnectionService.voiceState
    val transcription: StateFlow<String> = BleConnectionService.transcription
    val response: StateFlow<String> = BleConnectionService.response
    val errorMessage: StateFlow<String> = BleConnectionService.errorMessage
    val bleMode: StateFlow<Boolean> = BleConnectionService.bleMode

    init {
        viewModelScope.launch {
            BleConnectionService.connectionState.collect { state ->
                _bleConnectionState.value = state
            }
        }

        viewModelScope.launch {
            BleConnectionService.scannedDevices.collect { devices ->
                _availableBleDevices.value = devices
                val selectedAddress = _selectedBleDeviceAddress.value
                if (selectedAddress != null && devices.none { it.address == selectedAddress }) {
                    _selectedBleDeviceAddress.value = null
                }
                if (_selectedBleDeviceAddress.value == null && devices.size == 1) {
                    _selectedBleDeviceAddress.value = devices.first().address
                }
            }
        }

        viewModelScope.launch {
            BleConnectionService.preferredDevice.collect { device ->
                _preferredBleDevice.value = device
            }
        }

        viewModelScope.launch {
            BleConnectionService.batteryLevel.collect { level ->
                _batteryLevel.value = level
            }
        }
    }

    fun stopSpeaking() {
        BleConnectionService.stopSpeaking()
    }

    fun startBleScan() {
        _selectedBleDeviceAddress.value = null
        BleConnectionService.startScan()
    }

    fun selectBleDevice(address: String) {
        _selectedBleDeviceAddress.value = address
    }

    fun connectSelectedBleDevice() {
        val address = _selectedBleDeviceAddress.value ?: return
        BleConnectionService.setErrorMessage("")
        BleConnectionService.connectToDevice(address)
    }

    fun disconnectBleDevice() {
        BleConnectionService.disconnectProcessor()
        BleConnectionService.disconnectFromDevice()
    }
}
