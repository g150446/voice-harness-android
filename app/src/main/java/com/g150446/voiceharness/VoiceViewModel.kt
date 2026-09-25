package com.g150446.voiceharness

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class AppScreen {
    HOME, HISTORY_LIST, HISTORY_DETAIL, REMINDER_LIST, GESTURE_DIAG,
    HARBOR_DEVICES, HARBOR_WORKSPACES, HARBOR_DETAIL,
}

class VoiceViewModel(application: Application) : AndroidViewModel(application) {

    private val historyRepository = HistoryRepository(application)
    private val reminderRepository = ReminderRepository(application)

    private val _currentScreen = MutableStateFlow(AppScreen.HOME)
    val currentScreen: StateFlow<AppScreen> = _currentScreen

    private val _historyEntries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val historyEntries: StateFlow<List<HistoryEntry>> = _historyEntries

    private val _selectedHistoryEntry = MutableStateFlow<HistoryEntry?>(null)
    val selectedHistoryEntry: StateFlow<HistoryEntry?> = _selectedHistoryEntry

    private val _reminderEntries = MutableStateFlow<List<ReminderEntry>>(emptyList())
    val reminderEntries: StateFlow<List<ReminderEntry>> = _reminderEntries

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
    val harborInterpreting: StateFlow<Boolean> = BleConnectionService.harborInterpreting
    val response: StateFlow<String> = BleConnectionService.response
    val errorMessage: StateFlow<String> = BleConnectionService.errorMessage
    val bleMode: StateFlow<Boolean> = BleConnectionService.bleMode
    val isPrimary: StateFlow<Boolean> = BleConnectionService.isPrimary
    val doubleTapStatus: StateFlow<DoubleTapStatus> = BleConnectionService.doubleTapStatus
    val singleTapStatus: StateFlow<SingleTapStatus> = BleConnectionService.singleTapStatus
    val recordingTapMode: StateFlow<RecordingTapMode> = BleConnectionService.recordingTapMode
    val drivingMode: StateFlow<DrivingMode> = BleConnectionService.drivingMode
    val nodeDrivingMode: StateFlow<DrivingMode?> = BleConnectionService.nodeDrivingMode
    val nodePendingDrivingMode: StateFlow<DrivingMode?> =
        BleConnectionService.nodePendingDrivingMode
    val responseOutputTarget: StateFlow<ResponseOutputTarget> =
        BleConnectionService.responseOutputTarget
    val smartGlassesState: StateFlow<SmartGlassesState> =
        BleConnectionService.smartGlassesState
    val readingPassthroughEnabled: StateFlow<Boolean> =
        BleConnectionService.readingPassthroughEnabled
    val interactionMode: StateFlow<InteractionMode> = BleConnectionService.interactionMode
    val harborConnectionState: StateFlow<HarborConnectionState> =
        BleConnectionService.harborConnectionState
    val harborUiState: StateFlow<HarborUiState> = BleConnectionService.harborUiState
    private val _selectedHarborWorkspaceId = MutableStateFlow<String?>(null)
    val selectedHarborWorkspaceId: StateFlow<String?> = _selectedHarborWorkspaceId
    private val harborFontSizePrefs = HarborFontSizePreferences(application)
    private val _harborFontSize = MutableStateFlow(harborFontSizePrefs.size())
    val harborFontSize: StateFlow<Int> = _harborFontSize

    val gestureCaptureEnabled: StateFlow<Boolean> =
        BleConnectionService.gestureCaptureEnabled

    val nodeGestureCaptureEnabled: StateFlow<Boolean?> =
        BleConnectionService.nodeGestureCaptureEnabled

    val gestureDetectEnabled: StateFlow<Boolean> =
        BleConnectionService.gestureDetectEnabled

    val nodeGestureDetectEnabled: StateFlow<Boolean?> =
        BleConnectionService.nodeGestureDetectEnabled

    val recordingCueEnabled: StateFlow<Boolean> =
        BleConnectionService.recordingCueEnabled

    val lastPipelineMs: StateFlow<Long> = BleConnectionService.lastPipelineMs
    val modelStatus: StateFlow<ModelStatus> = ModelManager.status

    fun setOnDeviceProfile(profile: OnDeviceProfile) {
        BleConnectionService.switchOnDeviceProfile(getApplication(), profile)
    }

    private val blePreferences = BleConnectionPreferences(application)
    private val _connectionPriority = MutableStateFlow(blePreferences.connectionPriority())
    val connectionPriority: StateFlow<ConnectionPriority> = _connectionPriority

    fun setConnectionPriority(priority: ConnectionPriority) {
        blePreferences.setConnectionPriority(priority)
        _connectionPriority.value = priority
        if (_bleConnectionState.value == BleConnectionState.CONNECTED) {
            BleConnectionService.setRole(priority == ConnectionPriority.ANDROID)
        }
    }

    init {
        BleConnectionService.initializeResponseOutputTarget(application)
        BleConnectionService.initializeReadingPassthroughEnabled(application)
        ModelManager.refresh(application)

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

        // Follow Harbor wherever it was activated, including from voice. Only the workspace
        // list's tap used to move this, so a spoken switch left the workspace screen polling
        // the one it was opened on — alive, up to date, and about the wrong terminal.
        // The screen the user is on is not changed: following is not navigating.
        viewModelScope.launch {
            BleConnectionService.harborUiState.collect { state ->
                val activated = state.activatedWorkspaceId ?: return@collect
                if (activated != _selectedHarborWorkspaceId.value) {
                    _selectedHarborWorkspaceId.value = activated
                    BleConnectionService.loadHarborWorkspace(activated)
                }
            }
        }
    }

    fun stopSpeaking() {
        BleConnectionService.stopSpeaking()
    }

    fun setResponseOutputTarget(target: ResponseOutputTarget) {
        BleConnectionService.setResponseOutputTarget(getApplication(), target)
    }

    fun setReadingPassthroughEnabled(enabled: Boolean) {
        val applied = BleConnectionService.setInteractionMode(
            getApplication(),
            if (enabled) InteractionMode.READER else InteractionMode.AI,
        )
        if (enabled && !applied) {
            BleConnectionService.setResponse("リーダーモードにはG2プラグインの接続が必要です")
        }
    }

    fun setInteractionMode(mode: InteractionMode) {
        BleConnectionService.setInteractionMode(getApplication(), mode)
    }

    fun setHarborModeEnabled(enabled: Boolean) {
        BleConnectionService.setInteractionMode(
            getApplication(),
            if (enabled) InteractionMode.HARBOR else InteractionMode.AI,
        )
    }

    fun pairTerminalHarbor(rawUri: String) {
        BleConnectionService.pairTerminalHarbor(rawUri)
    }

    fun clearTerminalHarborPairing() {
        BleConnectionService.clearTerminalHarborPairing()
    }

    fun openTerminalHarbor() {
        _currentScreen.value = if (harborUiState.value.devices.isEmpty()) {
            AppScreen.HARBOR_DEVICES
        } else {
            BleConnectionService.refreshHarborWorkspaces()
            AppScreen.HARBOR_WORKSPACES
        }
    }

    fun showHarborDevices() { _currentScreen.value = AppScreen.HARBOR_DEVICES }
    fun selectHarborDevice(id: String) {
        BleConnectionService.selectHarborDevice(id)
        _currentScreen.value = AppScreen.HARBOR_WORKSPACES
    }
    fun removeHarborDevice(id: String) = BleConnectionService.removeHarborDevice(id)
    fun refreshHarborWorkspaces() = BleConnectionService.refreshHarborWorkspaces()
    fun createHarborWorkspace(root: String?) = BleConnectionService.createHarborWorkspace(root)
    fun closeHarborWorkspace(id: String) = BleConnectionService.closeHarborWorkspace(id)
    fun openHarborWorkspace(id: String) {
        _selectedHarborWorkspaceId.value = id
        BleConnectionService.activateHarborWorkspace(id)
        BleConnectionService.loadHarborWorkspace(id)
        _currentScreen.value = AppScreen.HARBOR_DETAIL
    }
    fun refreshHarborWorkspace(lines: Int = 500) {
        _selectedHarborWorkspaceId.value?.let { BleConnectionService.loadHarborWorkspace(it, lines) }
    }
    fun refreshHarborPlan() {
        _selectedHarborWorkspaceId.value?.let(BleConnectionService::loadHarborWorkspacePlan)
    }
    /** Newest page; pass a cursor to prepend the page before it. */
    fun refreshHarborTranscript(before: String? = null) {
        _selectedHarborWorkspaceId.value?.let {
            BleConnectionService.loadHarborWorkspaceTranscript(it, before)
        }
    }
    fun createHarborTab() = _selectedHarborWorkspaceId.value?.let(BleConnectionService::createHarborTab)
    fun activateHarborTab(id: String) = _selectedHarborWorkspaceId.value?.let {
        BleConnectionService.activateHarborTab(it, id)
    }
    fun closeHarborTab(id: String) = _selectedHarborWorkspaceId.value?.let {
        BleConnectionService.closeHarborTab(it, id)
    }
    fun sendHarborInstruction(text: String, submit: Boolean = true) =
        _selectedHarborWorkspaceId.value?.let {
            BleConnectionService.sendHarborInstruction(it, text, submit)
        }
    fun sendHarborKey(key: String) = _selectedHarborWorkspaceId.value?.let {
        BleConnectionService.sendHarborKey(it, key)
    }
    fun confirmHarborCommand() = BleConnectionService.confirmHarborCommand()
    fun cancelHarborCommand() = BleConnectionService.cancelHarborCommand()

    fun setHarborFontSize(size: Int) {
        val clamped = size.coerceIn(
            HarborFontSizePreferences.MIN_SIZE,
            HarborFontSizePreferences.MAX_SIZE,
        )
        harborFontSizePrefs.setSize(clamped)
        _harborFontSize.value = clamped
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

    fun openHistory() {
        _historyEntries.value = historyRepository.getAll()
        _currentScreen.value = AppScreen.HISTORY_LIST
    }

    fun openHistoryDetail(entry: HistoryEntry) {
        _selectedHistoryEntry.value = entry
        _currentScreen.value = AppScreen.HISTORY_DETAIL
    }

    fun setGestureLabel(entry: HistoryEntry, label: GestureLabel?) {
        // Toggling the label already shown clears it, so a misclick is undoable.
        val next = if (entry.gestureLabel == label) null else label
        if (!historyRepository.setGestureLabel(entry.id, next)) return
        _historyEntries.value = historyRepository.getAll()
        _selectedHistoryEntry.value = _historyEntries.value.firstOrNull { it.id == entry.id }
    }

    /**
     * Labels a whole block at once. A clinic session produces a run of accidental
     * triggers the user recognises as a group, and labelling them one by one is
     * the step most likely to be skipped.
     */
    fun labelHistoryRange(fromMs: Long, toMs: Long, label: GestureLabel?): Int {
        val count = historyRepository.setGestureLabelInRange(fromMs, toMs, label)
        if (count > 0) _historyEntries.value = historyRepository.getAll()
        return count
    }

    fun setGestureCaptureEnabled(enabled: Boolean) {
        BleConnectionService.setGestureCaptureEnabled(getApplication(), enabled)
    }

    fun setGestureDetectEnabled(enabled: Boolean) {
        BleConnectionService.setGestureDetectEnabled(getApplication(), enabled)
    }

    fun setRecordingCueEnabled(enabled: Boolean) {
        BleConnectionService.setRecordingCueEnabled(getApplication(), enabled)
    }

    fun setRecordingTapMode(mode: RecordingTapMode) {
        BleConnectionService.setRecordingTapMode(getApplication(), mode)
    }

    fun openReminders() {
        _reminderEntries.value = reminderRepository.getPending()
        _currentScreen.value = AppScreen.REMINDER_LIST
    }

    fun openGestureDiag() {
        _currentScreen.value = AppScreen.GESTURE_DIAG
    }

    fun clearGestureDiag() {
        GestureDiagStore.clear()
    }

    fun deleteReminder(id: String) {
        reminderRepository.deleteEntry(id)
        ReminderAlarmScheduler.cancel(getApplication(), id)
        _reminderEntries.value = reminderRepository.getPending()
    }

    fun navigateBack() {
        when (_currentScreen.value) {
            AppScreen.HISTORY_DETAIL -> _currentScreen.value = AppScreen.HISTORY_LIST
            AppScreen.HISTORY_LIST -> _currentScreen.value = AppScreen.HOME
            AppScreen.REMINDER_LIST -> _currentScreen.value = AppScreen.HOME
            AppScreen.GESTURE_DIAG -> _currentScreen.value = AppScreen.HOME
            AppScreen.HARBOR_DEVICES -> _currentScreen.value = AppScreen.HOME
            AppScreen.HARBOR_WORKSPACES -> _currentScreen.value = AppScreen.HOME
            AppScreen.HARBOR_DETAIL -> _currentScreen.value = AppScreen.HARBOR_WORKSPACES
            AppScreen.HOME -> {}
        }
    }
}
