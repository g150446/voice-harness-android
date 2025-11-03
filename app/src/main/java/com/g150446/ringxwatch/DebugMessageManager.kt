package com.g150446.ringxwatch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugMessageManager {
    private val _messages = MutableStateFlow<List<String>>(emptyList())
    val messages: StateFlow<List<String>> = _messages.asStateFlow()

    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun addMessage(message: String) {
        val timestamp = dateFormat.format(Date())
        val timestampedMessage = "[$timestamp] $message"
        _messages.value = _messages.value.takeLast(9) + timestampedMessage
    }

    fun clear() {
        _messages.value = emptyList()
    }
}
