package com.example.reviva.data.repository

import com.example.reviva.data.storage.InMemoryScanStore
import com.example.reviva.domain.model.ScanSession
import com.example.reviva.domain.repository.ScanRepository
import kotlinx.coroutines.flow.StateFlow

class ScanRepositoryImpl(
    private val store: InMemoryScanStore
) : ScanRepository {

    override val scanSession: StateFlow<ScanSession> = store.state

    override fun incrementCaptured() {
        store.update { it.copy(capturedCount = it.capturedCount + 1) }
    }

    override fun setStatus(status: String) {
        store.update { it.copy(status = status) }
    }

    override fun reset() {
        store.reset()
    }
}
