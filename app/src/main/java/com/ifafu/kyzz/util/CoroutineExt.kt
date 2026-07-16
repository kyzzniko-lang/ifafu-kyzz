package com.ifafu.kyzz.util

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ifafu.kyzz.data.network.AlertException
import com.ifafu.kyzz.ui.event.GlobalEvent
import com.ifafu.kyzz.ui.event.GlobalEventBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * ViewModel 协程安全启动：统一捕获异常并按约定处理。
 *
 * 约定：
 * - [CancellationException]：重抛（协程取消必须传播）
 * - [AlertException]：会话过期则全局跳登录，其余回调 onError
 * - 其他异常：日志记录 + 回调 onError
 *
 * 用法：
 * ```
 * launchSafe(onError = { msg -> _state.value = UiState.Error(msg) }) {
 *     _state.value = UiState.Loading
 *     val data = api.fetch()
 *     _state.value = UiState.Success(data)
 * }
 * ```
 *
 * 注意：本函数是给新代码用的可选增强。现有 ViewModel 各自的 try/catch 仍然有效，
 * 不强制迁移——仅在新写或重构时推荐使用，避免大面积回归风险。
 */
fun ViewModel.launchSafe(
    onError: (message: String) -> Unit = {},
    block: suspend CoroutineScope.() -> Unit
): Job {
    return viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AlertException) {
            Logger.e("launchSafe", "AlertException", e)
            if (e.isSessionExpired) {
                GlobalEventBus.emit(GlobalEvent.SessionExpired)
            }
            onError(if (e.isSessionExpired) "会话已过期，请重新登录" else (e.message ?: "操作失败"))
        } catch (e: Exception) {
            Logger.e("launchSafe", "coroutine error", e)
            onError("网络异常，请稍后重试")
        }
    }
}
