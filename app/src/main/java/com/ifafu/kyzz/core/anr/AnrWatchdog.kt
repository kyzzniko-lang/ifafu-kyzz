package com.ifafu.kyzz.core.anr

import android.os.Handler
import android.os.Looper
import com.ifafu.kyzz.core.crash.CrashReporter
import com.ifafu.kyzz.util.Logger

/**
 * 轻量 ANR 监测看门狗。
 *
 * 原理：后台守护线程向主线程 post 一个 tick，等待 [TIMEOUT_MS]，
 * 若主线程在该时间内没有处理，判定为疑似 ANR，记录日志（仅记录，不杀进程）。
 * 一次检测周期约为 [TIMEOUT_MS] + [COOLDOWN_MS]。
 *
 * 阈值保守（[TIMEOUT_MS] = 5s，与系统 ANR 阈值持平），避免误报。
 *
 * 注意：底层使用单个 Thread，**stop() 后不能再次 start()**（Thread 不可重启）。
 * 本应用中 watchdog 随 MainApplication 生命周期存在，从不 stop，故不受影响；
 * stop() 仅保留用于测试场景，且调用前应确保不再 start。
 */
class AnrWatchdog(
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val timeoutMs: Long = TIMEOUT_MS,
    private val cooldownMs: Long = COOLDOWN_MS
) {

    @Volatile private var running = false
    @Volatile private var tickReceived = false

    private val watchdogThread = Thread({ runLoop() }, "AnrWatchdog")

    fun start() {
        if (running) return
        running = true
        try {
            watchdogThread.isDaemon = true
            watchdogThread.start()
            Logger.i("AnrWatchdog", "started (timeout=${timeoutMs}ms, cooldown=${cooldownMs}ms)")
        } catch (e: Exception) {
            Logger.e("AnrWatchdog", "start failed", e)
        }
    }

    fun stop() {
        running = false
        try {
            watchdogThread.interrupt()
        } catch (_: Exception) {}
    }

    private fun runLoop() {
        while (running) {
            tickReceived = false
            // 向主线程投递一个 tick
            try {
                mainHandler.post { tickReceived = true }
            } catch (e: Exception) {
                Logger.e("AnrWatchdog", "post tick failed", e)
            }
            try {
                Thread.sleep(timeoutMs)
            } catch (_: InterruptedException) {
                break
            }
            if (!tickReceived && running) {
                // 主线程未在 timeout 内响应 → 疑似 ANR
                val blockedFor = timeoutMs
                Logger.w("AnrWatchdog", "ANR detected: main thread blocked > ${blockedFor}ms")
                try {
                    CrashReporter.reportAnr(threadName = "main", blockedForMs = blockedFor)
                } catch (e: Exception) {
                    Logger.e("AnrWatchdog", "report anr failed", e)
                }
            }
            // 检测周期间冷却，避免同一次阻塞反复记录，也避免空转过频
            try {
                Thread.sleep(cooldownMs)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    companion object {
        private const val TIMEOUT_MS = 5_000L
        private const val COOLDOWN_MS = 2_000L
    }
}
