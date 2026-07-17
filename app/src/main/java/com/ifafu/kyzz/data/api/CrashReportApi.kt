package com.ifafu.kyzz.data.api

import com.ifafu.kyzz.core.crash.CrashInfo
import com.ifafu.kyzz.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 崩溃/ANR 上报通道：复用 GitHubIssuesApi 的 token 模式，
 * 以 comment 形式发到 `ifafu-kyzz-comment` 仓库的 **Issue #3**。
 *
 * body 为结构化 JSON，方便后续聚合分析：
 * { type, time, appVersion, device, thread, message, trace, description }
 *
 * 失败静默返回 false，绝不影响用户。
 */
@Singleton
class CrashReportApi @Inject constructor(
    private val feedbackWorkerApi: FeedbackWorkerApi
) {
    companion object {
        private const val TAG = "CrashReportApi"
        private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }

    /**
     * 上报一次崩溃/ANR。
     * @param crashInfo 来自 [com.ifafu.kyzz.core.crash.CrashReporter] 的结构化信息
     * @param userDescription 用户可选补充的操作场景说明
     * @return 是否上报成功
     */
    suspend fun reportCrash(crashInfo: CrashInfo, userDescription: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val payload = buildMap<String, String> {
                    put("type", crashInfo.type)
                    put("time", DATE_FMT.format(Date(crashInfo.time)))
                    put("appVersion", crashInfo.appVersion)
                    put("device", crashInfo.deviceInfo)
                    put("thread", crashInfo.threadName)
                    put("message", crashInfo.message)
                    put("trace", crashInfo.trace)
                    if (userDescription.isNotBlank()) {
                        put("description", userDescription)
                    }
                }
                val ok = feedbackWorkerApi.post("/crash", payload)
                    ?.get("ok")?.asBoolean == true
                if (ok) Logger.i(TAG, "crash report sent successfully")
                else Logger.w(TAG, "crash report rejected by feedback service")
                ok
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "reportCrash error", e)
                false
            }
        }
}
