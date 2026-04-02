package com.g150446.voiceharness

import android.content.Context

data class BleDeviceInfo(
    val address: String,
    val name: String,
    val rssi: Int? = null
)

enum class ConnectionPriority { ANDROID, MAC_HANDY }

class BleConnectionPreferences(context: Context) {
    private val preferences =
        context.getSharedPreferences("ble_connection_prefs", Context.MODE_PRIVATE)

    fun preferredDevice(): BleDeviceInfo? {
        val address = preferences.getString(KEY_PREFERRED_DEVICE_ADDRESS, null) ?: return null
        val name = preferences.getString(KEY_PREFERRED_DEVICE_NAME, null).orEmpty()
        return BleDeviceInfo(
            address = address,
            name = if (name.isBlank()) address else name
        )
    }

    fun isAutoReconnectEnabled(): Boolean =
        preferences.getBoolean(KEY_AUTO_RECONNECT_ENABLED, false)

    fun savePreferredDevice(device: BleDeviceInfo, autoReconnectEnabled: Boolean) {
        preferences.edit()
            .putString(KEY_PREFERRED_DEVICE_ADDRESS, device.address)
            .putString(KEY_PREFERRED_DEVICE_NAME, device.name)
            .putBoolean(KEY_AUTO_RECONNECT_ENABLED, autoReconnectEnabled)
            .apply()
    }

    fun setAutoReconnectEnabled(enabled: Boolean) {
        preferences.edit()
            .putBoolean(KEY_AUTO_RECONNECT_ENABLED, enabled)
            .apply()
    }

    fun connectionPriority(): ConnectionPriority =
        ConnectionPriority.valueOf(
            preferences.getString(KEY_CONNECTION_PRIORITY, ConnectionPriority.ANDROID.name)!!
        )

    fun setConnectionPriority(priority: ConnectionPriority) {
        preferences.edit().putString(KEY_CONNECTION_PRIORITY, priority.name).apply()
    }

    companion object {
        private const val KEY_PREFERRED_DEVICE_ADDRESS = "preferred_device_address"
        private const val KEY_PREFERRED_DEVICE_NAME = "preferred_device_name"
        private const val KEY_AUTO_RECONNECT_ENABLED = "auto_reconnect_enabled"
        const val KEY_CONNECTION_PRIORITY = "connection_priority"
    }
}
