package com.ifafu.kyzz.data.api

import com.google.gson.Gson
import com.ifafu.kyzz.BuildConfig
import com.ifafu.kyzz.core.crash.CrashInfo
import com.ifafu.kyzz.data.util.KeyGuard
import com.ifafu.kyzz.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
    private val client: OkHttpClient
) {
    companion object {
        private const val TAG = "CrashReportApi"
        private const val OWNER = "kyzzniko-lang"
        private const val REPO = "ifafu-kyzz-comment"
        private const val CRASH_ISSUE = 3
        private val TOKEN = KeyGuard.decode(BuildConfig.GITHUB_TOKEN_ENC)
        private const val BASE = "https://api.github.com/repos/$OWNER/$REPO"
        private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }

    private val gson = Gson()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private fun isConfigured(): Boolean = TOKEN.isNotBlank()

    /**
     * 上报一次崩溃/ANR。
     * @param crashInfo 来自 [com.ifafu.kyzz.core.crash.CrashReporter] 的结构化信息
     * @param userDescription 用户可选补充的操作场景说明
     * @return 是否上报成功
     */
    suspend fun reportCrash(crashInfo: CrashInfo, userDescription: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!isConfigured()) {
                Logger.w(TAG, "token not configured, skip crash report")
                return@withContext false
            }
            try {
                val url = "$BASE/issues/$CRASH_ISSUE/comments"
                val bodyObj = buildMap {
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
                val innerJson = gson.toJson(bodyObj)
                val bodyJson = gson.toJson(mapOf("body" to innerJson))

                val request = Request.Builder().url(url).apply {
                    header("Authorization", "token $TOKEN")
                    header("Accept", "application/vnd.github.v3+json")
                }.post(bodyJson.toRequestBody(JSON)).build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Logger.i(TAG, "crash report sent successfully")
                        true
                    } else {
                        val respBody = response.body?.string()
                        Logger.e(TAG, "reportCrash failed: ${response.code} $respBody")
                        false
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "reportCrash error", e)
                false
            }
        }
}
