package com.ifafu.kyzz

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.StrictMode
import androidx.appcompat.app.AppCompatDelegate
import com.ifafu.kyzz.core.anr.AnrWatchdog
import com.ifafu.kyzz.core.crash.CrashReporter
import com.ifafu.kyzz.service.MockLocationService
import com.ifafu.kyzz.ui.main.MainActivity
import com.ifafu.kyzz.util.Logger
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MainApplication : Application() {

    private val anrWatchdog = AnrWatchdog()

    override fun onCreate() {
        super.onCreate()

        // 1. 初始化崩溃报告器（落盘 + 防循环计数）
        CrashReporter.init(this)

        // 2. 安装全局未捕获异常处理器（保留之前的默认 handler 链）
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(this))

        // 3. 日间/夜间模式
        val prefs = getSharedPreferences("ifafu_user", MODE_PRIVATE)
        val darkMode = prefs.getInt("dark_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(darkMode)

        // 4. 轻量 ANR 监测（主线程阻塞 > 5s 时记录日志）
        anrWatchdog.start()

        // 5. debug 构建开启 StrictMode，抓主线程磁盘/网络违规
        if (BuildConfig.DEBUG) {
            enableStrictMode()
        }

        Logger.i("MainApplication", "onCreate complete, version=${BuildConfig.VERSION_NAME}")
    }

    /**
     * 全局未捕获异常处理器。
     *
     * 流程：
     * 1. 日志记录（Logger.e 始终输出）
     * 2. 调用 CrashReporter 落盘 + 标记 pending + 防崩溃循环判定
     * 3. 若进入崩溃循环：停止 MockLocationService 并重置计数（避免无限重启）
     * 4. 拉起 MainActivity 并带 crash_recovery extra（MainActivity 据此弹恢复对话框）
     * 5. 委托给默认 handler 让进程正常终止
     *
     * 每一步都用 try-catch 保护，确保 handler 自身不会再次崩溃。
     */
    private class CrashHandler(private val app: Application) : Thread.UncaughtExceptionHandler {
        private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            Logger.e("iFAFU", "Uncaught exception on ${thread.name}", throwable)
            // 1. 落盘 + 防崩溃循环判定
            val inCrashLoop = try {
                CrashReporter.reportCrash(thread.name, throwable)
            } catch (_: Exception) {
                false
            }
            // 2. 崩溃循环保护：停掉模拟定位服务，重置计数
            if (inCrashLoop) {
                try {
                    app.getSharedPreferences("ifafu_user", MODE_PRIVATE).edit()
                        .putBoolean("mock_location_running", false)
                        .apply()
                    app.stopService(Intent(app, MockLocationService::class.java))
                } catch (_: Exception) {}
                try {
                    CrashReporter.resetCrashLoopCount()
                } catch (_: Exception) {}
            }
            // 3. 拉起 MainActivity 恢复
            try {
                val intent = Intent(app, MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                intent.putExtra(EXTRA_CRASH_RECOVERY, true)
                app.startActivity(intent)
            } catch (_: Exception) {}
            // 4. 委托默认 handler 让进程正常终止
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun enableStrictMode() {
        try {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectActivityLeaks()
                    .detectLeakedClosableObjects()
                    .detectLeakedSqlLiteObjects()
                    .penaltyLog()
                    .build()
            )
        } catch (e: Exception) {
            Logger.w("MainApplication", "enableStrictMode failed", e)
        }
    }

    companion object {
        /** MainActivity intent extra：标记本次启动由崩溃恢复触发。 */
        const val EXTRA_CRASH_RECOVERY = "crash_recovery"
    }
}
