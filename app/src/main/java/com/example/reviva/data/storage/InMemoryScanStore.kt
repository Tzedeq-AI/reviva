package com.example.reviva.data.storage

import com.example.reviva.domain.model.ScanSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class InMemoryScanStore {

    private val _state = MutableStateFlow(ScanSession())
    val state: StateFlow<ScanSession> = _state.asStateFlow()

    fun update(transform: (ScanSession) -> ScanSession) {
        _state.value = transform(_state.value)
    }

    fun reset() {
        _state.value = ScanSession()
    }
}
