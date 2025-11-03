package com.g150446.ringxwatch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackState(
    val isPlaying: Boolean = false,
    val currentTrack: String = "No track"
)

object PlaybackStateManager {
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    fun updateState(isPlaying: Boolean, currentTrack: String) {
        _state.value = PlaybackState(isPlaying, currentTrack)
    }

    fun updatePlayingState(isPlaying: Boolean) {
        _state.value = _state.value.copy(isPlaying = isPlaying)
    }

    fun updateTrack(currentTrack: String) {
        _state.value = _state.value.copy(currentTrack = currentTrack)
    }
}
