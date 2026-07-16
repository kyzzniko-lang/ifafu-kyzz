package com.ifafu.kyzz.ui.event

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.ifafu.kyzz.util.Logger

/**
 * 一次性事件包装：保证事件只被消费一次，避免 LiveData 配置变更后重复触发
 * （经典 SingleLiveEvent 思路）。
 */
class Event<out T>(private val content: T) {
    private var hasBeenHandled = false

    /** 若事件尚未被消费，返回内容；否则返回 null。 */
    fun getContentIfNotHandled(): T? {
        return if (hasBeenHandled) {
            null
        } else {
            hasBeenHandled = true
            content
        }
    }

    /** 无论是否消费过都返回内容（用于日志/调试）。 */
    fun peekContent(): T = content
}

/**
 * 全局事件类型。
 * - [SessionExpired]：教务系统会话过期，订阅方应统一跳转登录页
 * - [Toast]：全局轻量提示
 * - [CrashFeedbackPending]：检测到上次崩溃未反馈
 */
sealed class GlobalEvent {
    object SessionExpired : GlobalEvent()
    data class Toast(val message: String) : GlobalEvent()
    object CrashFeedbackPending : GlobalEvent()
}

/**
 * 全局事件总线。任何线程均可 emit，UI 层在 BaseActivity 统一订阅。
 *
 * 实现细节：用主线程 Handler + setValue，而不是 LiveData.postValue——后者在
 * 短时间内连续多次调用时只派发最后一个值（官方文档明确行为），会丢失会话过期等
 * 关键事件。这里通过 Handler 切到主线程后用 setValue，保证每个事件都被派发。
 */
object GlobalEventBus {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val _events = MutableLiveData<Event<GlobalEvent>>()
    val events: LiveData<Event<GlobalEvent>> = _events

    fun emit(event: GlobalEvent) {
        Logger.i("GlobalEventBus", "emit: $event")
        if (Looper.myLooper() == Looper.getMainLooper()) {
            _events.setValue(Event(event))
        } else {
            mainHandler.post { _events.setValue(Event(event)) }
        }
    }
}
