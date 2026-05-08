package com.ifafu.kyzz.ui.base

sealed class UiState<out T> {
    object Idle : UiState<Nothing>()
    object Loading : UiState<Nothing>()
    data class Success<T>(val data: T) : UiState<T>()
    data class Cached<T>(val data: T, val staleMessage: String = "离线数据") : UiState<T>()
    data class Error(val message: String) : UiState<Nothing>()
}
