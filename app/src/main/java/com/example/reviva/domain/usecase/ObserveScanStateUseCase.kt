package com.example.reviva.domain.usecase

import com.example.reviva.domain.repository.ScanRepository
import javax.inject.Inject

class ObserveScanStateUseCase @Inject constructor(
    private val repository: ScanRepository
) {
    operator fun invoke() = repository.scanSession
}
