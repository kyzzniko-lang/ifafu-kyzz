package com.ifafu.kyzz.util

import android.util.Log
import com.ifafu.kyzz.BuildConfig

/**
 * 统一日志工具，替代散落的 android.util.Log。
 *
 * - e()/w() 始终输出（崩溃诊断需要）
 * - d()/i()/v() 仅在 debug 构建输出，release 自动屏蔽
 *
 * 用法：Logger.d("msg") / Logger.e(TAG, "msg", e) / Logger.e("msg", e)
 */
object Logger {

    private const val DEFAULT_TAG = "iFAFU"

    @JvmStatic
    fun v(tag: String = DEFAULT_TAG, msg: String, t: Throwable? = null) {
        if (BuildConfig.DEBUG) log(Log.VERBOSE, tag, msg, t)
    }

    @JvmStatic
    fun d(tag: String = DEFAULT_TAG, msg: String, t: Throwable? = null) {
        if (BuildConfig.DEBUG) log(Log.DEBUG, tag, msg, t)
    }

    @JvmStatic
    fun i(tag: String = DEFAULT_TAG, msg: String, t: Throwable? = null) {
        if (BuildConfig.DEBUG) log(Log.INFO, tag, msg, t)
    }

    @JvmStatic
    fun w(tag: String = DEFAULT_TAG, msg: String, t: Throwable? = null) {
        log(Log.WARN, tag, msg, t)
    }

    /**
     * 始终输出的错误日志。优先用于异常/崩溃诊断。
     * 允许只传 message，或 message + throwable。
     */
    @JvmStatic
    fun e(tag: String = DEFAULT_TAG, msg: String, t: Throwable? = null) {
        log(Log.ERROR, tag, msg, t)
    }

    /** 便捷重载：直接传 throwable，自动取其 message。 */
    @JvmStatic
    fun e(tag: String = DEFAULT_TAG, t: Throwable) {
        log(Log.ERROR, tag, t.message ?: t.javaClass.simpleName, t)
    }

    private fun log(priority: Int, tag: String, msg: String, t: Throwable?) {
        // 超长日志分段打印，避免 logcat 截断（如崩溃栈）
        val full = if (t != null) {
            "$msg\n${Log.getStackTraceString(t)}"
        } else {
            msg
        }
        if (full.length <= 3500) {
            Log.println(priority, tag, full)
            return
        }
        var idx = 0
        val chunkSize = 3500
        while (idx < full.length) {
            val end = (idx + chunkSize).coerceAtMost(full.length)
            Log.println(priority, tag, full.substring(idx, end))
            idx = end
        }
    }
}
