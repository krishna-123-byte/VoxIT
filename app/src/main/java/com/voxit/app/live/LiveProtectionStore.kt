package com.voxit.app.live

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

object LiveProtectionStore {
    private val _state = MutableStateFlow(LiveProtectionState())
    val state = _state.asStateFlow()
    private val _openLiveRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 2)
    val openLiveRequests = _openLiveRequests.asSharedFlow()

    @Volatile var appVisible: Boolean = false

    fun update(transform: (LiveProtectionState) -> LiveProtectionState) { _state.value = transform(_state.value) }
    fun replace(state: LiveProtectionState) { _state.value = state }
    fun requestOpenLive() { _openLiveRequests.tryEmit(Unit) }
}
