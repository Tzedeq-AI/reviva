package com.example.reviva.domain.repository

import com.example.reviva.domain.model.ScanSession
import kotlinx.coroutines.flow.StateFlow

interface ScanRepository {
    val scanSession: StateFlow<ScanSession>

    fun incrementCaptured()
    fun setStatus(status: String)
    fun reset()
}
