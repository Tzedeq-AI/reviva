package com.example.reviva.presentation.camera.state

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ScanFlowViewModel : ViewModel() {

    private val _capturedCount = MutableStateFlow(0)
    val capturedCount: StateFlow<Int> = _capturedCount.asStateFlow()

    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status.asStateFlow()

    fun incrementCaptured() {
        _capturedCount.value += 1
    }

    fun setStatus(newStatus: String) {
        _status.value = newStatus
    }

    fun reset() {
        _capturedCount.value = 0
        _status.value = "Idle"
    }
}
