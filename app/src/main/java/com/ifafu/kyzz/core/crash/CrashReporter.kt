package com.ifafu.kyzz.core.crash

import android.app.Application
import android.content.Context
import android.os.Build
import com.ifafu.kyzz.BuildConfig
import com.ifafu.kyzz.util.AppUtil
import com.ifafu.kyzz.util.Logger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一次崩溃/ANR 的结构化信息。用于落盘、读取展示、上报反馈。
 */
data class CrashInfo(
    val type: String,            // "crash" | "anr"
    val threadName: String,      // 发生崩溃的线程
    val message: String,         // throwable.message 或 ANR 描述
    val trace: String,           // 完整堆栈
    val time: Long,              // 发生时间戳
    val appVersion: String,      // 应用版本名
    val deviceInfo: String,      // 机型/Android版本/厂商等
    val filePath: String? = null // 落盘文件路径（上报后用于定位）
)

/**
 * 崩溃报告器：负责崩溃/ANR 的**落盘**、**防崩溃循环**计数，以及 pending 报告的读取/清除。
 *
 * 设计要点：
 * - 所有写文件操作全程 try-catch，绝不让报告器自身抛异常（它运行在崩溃边缘）。
 * - 保留旧版 `ifafu_crash` SharedPreferences 的防循环逻辑（10s 内 >=3 次停服务）。
 * - 文件保留最近 [MAX_FILES] 个，旧的自动清理，避免无限增长。
 *
 * 用法：MainApplication.CrashHandler 捕获异常后调用 [reportCrash]；
 * MainActivity 启动后调用 [getPendingCrashReport] 判断是否需要弹恢复对话框。
 */
object CrashReporter {

    private const val PREF_NAME = "ifafu_crash"
    private const val KEY_PENDING = "pending_crash_feedback"
    private const val KEY_PENDING_FILE = "pending_crash_file"
    private const val KEY_CRASH_COUNT = "crash_count"
    private const val KEY_LAST_CRASH_TIME = "last_crash_time"

    /** 10 秒窗口内连续崩溃达到此阈值，判定为崩溃循环，需要采取保护措施。 */
    private const val CRASH_LOOP_THRESHOLD = 3
    private const val CRASH_LOOP_WINDOW_MS = 10_000L

    private const val DIR_CRASH = "crash"
    private const val MAX_FILES = 10

    private lateinit var appContext: Context
    private val crashDir: File by lazy { File(appContext.filesDir, DIR_CRASH) }
    private val prefs by lazy { appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE) }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    fun init(context: Context) {
        appContext = context.applicationContext
        try {
            if (!crashDir.exists()) crashDir.mkdirs()
        } catch (e: Exception) {
            Logger.e("CrashReporter", "init crash dir failed", e)
        }
    }

    /**
     * 记录一次崩溃。返回是否进入崩溃循环（调用方据此停止 MockLocationService 等）。
     */
    @Synchronized
    fun reportCrash(
        threadName: String,
        throwable: Throwable,
        type: String = "crash"
    ): Boolean {
        val now = System.currentTimeMillis()
        val trace = throwable.stackTraceToString()
        val message = throwable.message ?: throwable.javaClass.name
        val info = CrashInfo(
            type = type,
            threadName = threadName,
            message = message,
            trace = trace,
            time = now,
            appVersion = safeAppVersion(),
            deviceInfo = collectDeviceInfo()
        )

        // 1. 落盘
        val file = writeToFile(info)

        // 2. 更新 pending 标记（供 MainActivity 启动时检测）
        try {
            prefs.edit()
                .putBoolean(KEY_PENDING, true)
                .putString(KEY_PENDING_FILE, file?.absolutePath ?: "")
                .apply()
        } catch (e: Exception) {
            Logger.e("CrashReporter", "update pending pref failed", e)
        }

        // 3. 防崩溃循环计数
        return isInCrashLoop(now)
    }

    /**
     * 记录一次 ANR（不走崩溃循环逻辑，仅落盘 + 标记 pending）。
     */
    @Synchronized
    fun reportAnr(threadName: String, blockedForMs: Long) {
        val now = System.currentTimeMillis()
        val msg = "主线程疑似阻塞 ${blockedForMs}ms（ANR）"
        val info = CrashInfo(
            type = "anr",
            threadName = threadName,
            message = msg,
            trace = "(ANR via watchdog，无堆栈)",
            time = now,
            appVersion = safeAppVersion(),
            deviceInfo = collectDeviceInfo()
        )
        val file = writeToFile(info)
        // ANR 也标记为 pending，让用户在下次启动有机会反馈
        try {
            prefs.edit()
                .putBoolean(KEY_PENDING, true)
                .putString(KEY_PENDING_FILE, file?.absolutePath ?: "")
                .apply()
        } catch (e: Exception) {
            Logger.e("CrashReporter", "mark pending(anr) failed", e)
        }
    }

    /**
     * 读取待反馈的崩溃报告（启动时调用）。没有则返回 null。
     */
    fun getPendingCrashReport(): CrashInfo? {
        return try {
            if (!prefs.getBoolean(KEY_PENDING, false)) return null
            val path = prefs.getString(KEY_PENDING_FILE, null)
            if (path.isNullOrEmpty()) return null
            val file = File(path)
            if (!file.exists()) return null
            parseFile(file)
        } catch (e: Exception) {
            Logger.e("CrashReporter", "getPendingCrashReport failed", e)
            null
        }
    }

    /**
     * 清除 pending 标记（用户忽略或已反馈后调用）。不删除文件（保留历史）。
     */
    fun clearPendingCrash() {
        try {
            prefs.edit()
                .putBoolean(KEY_PENDING, false)
                .putString(KEY_PENDING_FILE, "")
                .apply()
        } catch (e: Exception) {
            Logger.e("CrashReporter", "clearPendingCrash failed", e)
        }
    }

    /** 是否有待反馈的崩溃。轻量判断，不读文件。 */
    fun hasPendingCrash(): Boolean = prefs.getBoolean(KEY_PENDING, false)

    /**
     * 重置崩溃循环计数器（防循环措施触发后调用）。
     */
    fun resetCrashLoopCount() {
        try {
            prefs.edit().putInt(KEY_CRASH_COUNT, 0).apply()
        } catch (e: Exception) {
            Logger.e("CrashReporter", "resetCrashLoopCount failed", e)
        }
    }

    private fun isInCrashLoop(now: Long): Boolean {
        val lastTime = prefs.getLong(KEY_LAST_CRASH_TIME, 0L)
        val prevCount = prefs.getInt(KEY_CRASH_COUNT, 0)
        val recentCount = if (now - lastTime < CRASH_LOOP_WINDOW_MS) prevCount + 1 else 1
        try {
            prefs.edit()
                .putLong(KEY_LAST_CRASH_TIME, now)
                .putInt(KEY_CRASH_COUNT, recentCount)
                .apply()
        } catch (e: Exception) {
            Logger.e("CrashReporter", "update crash count failed", e)
        }
        return recentCount >= CRASH_LOOP_THRESHOLD
    }

    private fun writeToFile(info: CrashInfo): File? {
        return try {
            if (!crashDir.exists()) crashDir.mkdirs()
            val fileName = "${info.type}_${info.time}.txt"
            val file = File(crashDir, fileName)
            file.writeText(formatReport(info))
            trimOldFiles()
            file
        } catch (e: Exception) {
            Logger.e("CrashReporter", "write crash file failed", e)
            null
        }
    }

    private fun trimOldFiles() {
        try {
            val files = crashDir.listFiles()?.sortedBy { it.lastModified() } ?: return
            if (files.size > MAX_FILES) {
                files.take(files.size - MAX_FILES).forEach { it.delete() }
            }
        } catch (e: Exception) {
            Logger.e("CrashReporter", "trim old files failed", e)
        }
    }

    private fun formatReport(info: CrashInfo): String = buildString {
        appendLine("===== iFAFU ${info.type.uppercase()} REPORT =====")
        appendLine("Time: ${dateFormat.format(Date(info.time))}")
        appendLine("AppVersion: ${info.appVersion}")
        appendLine("Thread: ${info.threadName}")
        appendLine("DeviceInfo: ${info.deviceInfo}")
        appendLine("Message: ${info.message}")
        appendLine("----- StackTrace -----")
        appendLine(info.trace)
        appendLine("===== END =====")
    }

    private fun parseFile(file: File): CrashInfo? {
        return try {
            val text = file.readText()
            // type 直接按文件名前缀判定（文件名形如 crash_<ts>.txt / anr_<ts>.txt）
            val type = if (file.name.startsWith("anr")) "anr" else "crash"
            val time = file.name.substringAfter('_').substringBefore('.').toLongOrNull()
                ?: System.currentTimeMillis()
            CrashInfo(
                type = type,
                threadName = extractLine(text, "Thread") ?: "unknown",
                message = extractLine(text, "Message") ?: "Unknown error",
                trace = text.substringAfter("----- StackTrace -----\n", text)
                    .substringBefore("===== END =====").trim(),
                time = time,
                appVersion = extractLine(text, "AppVersion") ?: "unknown",
                deviceInfo = extractLine(text, "DeviceInfo") ?: "unknown",
                filePath = file.absolutePath
            )
        } catch (e: Exception) {
            Logger.e("CrashReporter", "parse crash file failed", e)
            null
        }
    }

    private fun extractLine(text: String, key: String): String? {
        return text.lineSequence()
            .firstOrNull { it.startsWith("$key:") }
            ?.substringAfter(':')
            ?.trim()
    }

    private fun safeAppVersion(): String {
        return if (this::appContext.isInitialized) {
            "${AppUtil.getLocalVersionName(appContext)} (${BuildConfig.VERSION_CODE})"
        } else {
            "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        }
    }

    private fun collectDeviceInfo(): String {
        return buildString {
            append("Brand=").append(Build.BRAND).append("; ")
            append("Model=").append(Build.MODEL).append("; ")
            append("Manufacturer=").append(Build.MANUFACTURER).append("; ")
            append("Android=").append(Build.VERSION.RELEASE)
            append("(API ").append(Build.VERSION.SDK_INT).append("); ")
            append("Abi=").append(Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
        }
    }
}
