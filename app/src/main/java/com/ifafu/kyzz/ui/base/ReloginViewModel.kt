package com.ifafu.kyzz.ui.base

import androidx.lifecycle.ViewModel
import com.ifafu.kyzz.data.network.AlertException
import com.ifafu.kyzz.ui.event.GlobalEvent
import com.ifafu.kyzz.ui.event.GlobalEventBus
import com.ifafu.kyzz.util.Logger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlin.coroutines.CoroutineContext

/**
 * 应用所有 ViewModel 的抽象基类。
 *
 * 提供：
 * 1. 统一的 [coroutineExceptionHandler]——可作为 `viewModelScope.launch(handler)` 的兜底，
 *    对未捕获的异常按约定处理（会话过期全局跳登录，其余走日志 + [onUnhandledError]）。
 * 2. [onUnhandledError]——子类可选覆盖，为未处理错误提供 UI 兜底（默认空实现）。
 *
 * 向后兼容：现有子类仍可用各自的 try/catch，handler 只是可选增强。
 * 新代码推荐使用 [com.ifafu.kyzz.util.launchSafe] 扩展函数。
 */
abstract class ReloginViewModel : ViewModel() {

    /**
     * 可复用的协程异常处理器。用法：
     * ```
     * viewModelScope.launch(coroutineExceptionHandler) { ... }
     * ```
     */
    protected val coroutineExceptionHandler = CoroutineExceptionHandler { _: CoroutineContext, e: Throwable ->
        Logger.e(TAG, "unhandled coroutine exception", e)
        when (e) {
            is AlertException -> {
                if (e.isSessionExpired) {
                    GlobalEventBus.emit(GlobalEvent.SessionExpired)
                }
                onUnhandledError(if (e.isSessionExpired) "会话已过期，请重新登录" else (e.message ?: "操作失败"))
            }
            else -> onUnhandledError("网络异常，请稍后重试")
        }
    }

    /**
     * 子类可选覆盖：协程中出现未处理错误时的兜底回调（默认空实现，不强制改）。
     * 典型用法：覆写为 `_state.value = UiState.Error(message)`。
     */
    protected open fun onUnhandledError(message: String) {}

    companion object {
        private const val TAG = "ReloginViewModel"
    }
}
