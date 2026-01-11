package com.example.reviva.presentation.home

import com.example.reviva.presentation.base.BaseViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class HomeViewModel : BaseViewModel() {

    private val _uiState = MutableStateFlow("Idle")
    val uiState: StateFlow<String> = _uiState

    fun setState(state: String) {
        _uiState.value = state
    }
}
