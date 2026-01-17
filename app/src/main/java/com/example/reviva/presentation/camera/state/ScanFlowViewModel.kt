package com.example.reviva.presentation.camera.state

import androidx.lifecycle.ViewModel
import com.example.reviva.domain.repository.ScanRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ScanFlowViewModel @Inject constructor(
    private val repository: ScanRepository
) : ViewModel() {

    val scanSession = repository.scanSession

    fun incrementCaptured() = repository.incrementCaptured()

    fun setStatus(status: String) = repository.setStatus(status)

    fun reset() = repository.reset()
}
