package com.ifafu.kyzz

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Looper
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
        // 首次安装没有保存过主题偏好时固定使用浅色模式，避免跟随系统深色主题
        // 导致应用第一次启动直接进入黑色 UI；用户手动切换后仍使用已保存的值。
        val darkMode = prefs.getInt("dark_mode", AppCompatDelegate.MODE_NIGHT_NO)
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
            // logcat 也会被 bugreport/adb 采集：先脱敏再打印（CrashReporter 落盘的
            // 同样是脱敏后的内容），异常消息里可能内嵌会话 token/学号
            try {
                Logger.e(
                    "iFAFU",
                    com.ifafu.kyzz.core.crash.TraceSanitizer.sanitize(
                        "Uncaught exception on ${thread.name}\n${throwable.stackTraceToString()}"
                    )
                )
            } catch (_: Throwable) {}
            // 1. 落盘 + 防崩溃循环判定。catch Throwable 而非 Exception：
            // OOM/StackOverflowError 一族的 Error 若在此逃逸，会跳过循环保护与
            // 恢复拉起直接交给系统默认 handler。reportCrash 自身再抛的任何
            // Throwable 都被吞掉，不会递归进入 uncaughtException。
            val inCrashLoop = try {
                CrashReporter.reportCrash(thread.name, throwable)
            } catch (_: Throwable) {
                false
            }
            // 2. 崩溃循环保护：停掉模拟定位服务，重置计数
            if (inCrashLoop) {
                try {
                    app.getSharedPreferences("ifafu_user", MODE_PRIVATE).edit()
                        .putBoolean("mock_location_running", false)
                        .commit()
                    app.stopService(Intent(app, MockLocationService::class.java))
                } catch (_: Exception) {}
                try {
                    CrashReporter.resetCrashLoopCount()
                } catch (_: Exception) {}
                // 循环保护触发时不再拉起 MainActivity：若崩溃点就在 MainActivity
                // 启动路径上（恢复的 Fragment/损坏状态），"拉起→崩溃→再拉起"
                // 会无限循环，且计数每次被重置，保护形同虚设。只落日志交给系统。
                defaultHandler?.uncaughtException(thread, throwable)
                return
            }
            // 3. 拉起 MainActivity 恢复：仅主线程崩溃才拉起。后台线程（widget
            //    更新/后台任务）崩溃时用户可能在使用其他应用，把 iFAFU 拉到
            //    前台非常突兀；后台崩溃照常落盘，下次启动仍会弹恢复对话框。
            if (thread === Looper.getMainLooper().thread) {
                try {
                    val intent = Intent(app, MainActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    intent.putExtra(EXTRA_CRASH_RECOVERY, true)
                    app.startActivity(intent)
                } catch (_: Exception) {}
            }
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

        // 本进程内已有 Activity 完成过 onCreate。
        // 区分"进程内配置变更重建"（可安全恢复状态）与"进程死亡后重建"
        // （曾触发 Hilt 注入与 Fragment 恢复冲突崩溃，见 BaseActivity）。
        @Volatile
        var processWarmed = false
    }
}
