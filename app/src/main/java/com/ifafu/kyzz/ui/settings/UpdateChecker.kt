package com.ifafu.kyzz.ui.settings

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object UpdateChecker {

    private const val REPO = "kyzzniko-lang/ifafu-kyzz"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val APK_NOT_READY_RETRY_COUNT = 3
    private const val APK_NOT_READY_RETRY_DELAY_MS = 10_000L

    // 单例协程 scope：用于无显式 scope 传入时的兜底。
    // 通过 currentCheckJob 跟踪并取消上一次未完成的检查，避免多个并发检查叠加，
    // 也避免旧回调（持有已销毁的 Fragment/Activity）在协程里继续持有引用。
    private val checkScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var currentCheckJob: kotlinx.coroutines.Job? = null
    private val checkGeneration = AtomicLong(0L)

    data class ReleaseInfo(
        @SerializedName("tag_name") val tagName: String,
        @SerializedName("body") val body: String?,
        @SerializedName("assets") val assets: List<Asset>?
    ) {
        data class Asset(
            @SerializedName("name") val name: String,
            @SerializedName("browser_download_url") val downloadUrl: String,
            @SerializedName("size") val size: Long
        )

        val versionName: String get() = tagName.removePrefix("v")
        val apkAsset: Asset? get() = assets
            ?.filter { it.name.endsWith(".apk", ignoreCase = true) }
            ?.maxByOrNull { it.size }
    }

    sealed class CheckResult {
        data class UpdateAvailable(val release: ReleaseInfo) : CheckResult()
        /** 当前安装版本已经不低于线上版本；提示必须展示本机版本。 */
        data class UpToDate(val currentVersion: String) : CheckResult()
        data class ReleaseNotReady(val latestVersion: String) : CheckResult()
        data class Failed(val message: String? = null) : CheckResult()
    }

    /**
     * 检查更新。回调始终在主线程执行。
     *
     * 注意：context 仅用于读取应用版本号（内部转 applicationContext），不会跨协程持有
     * 调用方的 Activity/Fragment 引用。若连续调用，会取消上一次未完成的检查。
     */
    fun checkForUpdate(context: Context, callback: (CheckResult) -> Unit) {
        // 取消上一次未完成的检查，避免并发叠加 & 旧回调持有已销毁组件
        currentCheckJob?.cancel()
        val generation = checkGeneration.incrementAndGet()
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val appContext = context.applicationContext // 仅持有 application context，避免泄漏 Activity
        fun deliver(result: CheckResult) {
            mainHandler.post {
                // Blocking OkHttp calls may finish after coroutine cancellation.
                // Never let an older response overwrite a newer release card.
                if (generation == checkGeneration.get()) callback(result)
            }
        }
        currentCheckJob = checkScope.launch {
            try {
                var latestVersionWithoutApk: String? = null
                repeat(APK_NOT_READY_RETRY_COUNT) { attempt ->
                    android.util.Log.i("UpdateChecker", "Checking: $API_URL, attempt=${attempt + 1}")
                    val request = Request.Builder()
                        .url(API_URL)
                        .header("Accept", "application/vnd.github.v3+json")
                        .header("Cache-Control", "no-cache")
                        .header("Pragma", "no-cache")
                        .build()
                    val body = executeCancellable(request)
                    val release = Gson().fromJson(body, ReleaseInfo::class.java)
                    val currentVersion = getCurrentVersion(appContext)
                    val isNewer = isNewerVersion(release.versionName, currentVersion)
                    val hasApk = release.apkAsset != null
                    android.util.Log.i("UpdateChecker", "Remote: ${release.versionName}, Local: $currentVersion, isNewer: $isNewer, hasApk: $hasApk, APK: ${release.apkAsset?.name}")

                    if (isNewer && hasApk) {
                        deliver(CheckResult.UpdateAvailable(release))
                        return@launch
                    }
                    if (!isNewer) {
                        // 本机可能比 GitHub 最新 release 更高（例如测试中的 2.6.0
                        // 对线上 2.5.9）。此处若返回远端版本，UI 会显示
                        // “已是最新版本 v2.5.9”，与设置页的 2.6.0 (26) 自相矛盾。
                        deliver(CheckResult.UpToDate(currentVersion))
                        return@launch
                    }
                    latestVersionWithoutApk = release.versionName
                    if (attempt < APK_NOT_READY_RETRY_COUNT - 1) {
                        delay(APK_NOT_READY_RETRY_DELAY_MS)
                    }
                }
                deliver(CheckResult.ReleaseNotReady(latestVersionWithoutApk ?: "unknown"))
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 协程取消（被新的检查取消或调用方主动取消），不回调
            } catch (e: Exception) {
                android.util.Log.e("UpdateChecker", "Check failed: ${e.javaClass.simpleName}: ${e.message}")
                deliver(CheckResult.Failed(e.message))
            }
        }
    }

    fun isChecking(): Boolean = currentCheckJob?.isActive == true

    private suspend fun executeCancellable(request: Request): String =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: Call, response: okhttp3.Response) {
                    try {
                        response.use {
                            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                            val body = it.body?.string() ?: throw IOException("响应内容为空")
                            if (continuation.isActive) continuation.resumeWith(Result.success(body))
                        }
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                    }
                }
            })
        }

    fun getCurrentVersion(context: Context): String {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: "0.0.0"
        } catch (_: PackageManager.NameNotFoundException) {
            "0.0.0"
        }
    }

    fun isNewerVersion(latest: String, current: String): Boolean {
        fun numericParts(version: String): List<Long> = version
            .trim()
            .removePrefix("v")
            .removePrefix("V")
            .split(".")
            .map { it.takeWhile(Char::isDigit).toLongOrNull() ?: 0L }

        val latestParts = numericParts(latest)
        val currentParts = numericParts(current)
        val maxLen = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    fun isNewerThanCurrent(context: Context, release: ReleaseInfo): Boolean {
        return isNewerVersion(release.versionName, getCurrentVersion(context))
    }

    private val mirrors = listOf(
        "https://gh-proxy.com",
        "https://ghfast.top",
        "https://mirror.ghproxy.com"
    )

    @Volatile
    private var isDownloading = false
    private val downloadStartLock = Any()

    fun downloadAndInstall(context: Context, release: ReleaseInfo) {
        // check-then-act 必须原子：@Volatile 只保证可见性，双击/两入口并发调用
        // 都会通过检查，各自 enqueue 一个 DownloadManager 写同一文件并注册两个
        // receiver，完成时弹两个安装器。
        synchronized(downloadStartLock) {
            if (isDownloading) return
            release.apkAsset ?: return
            isDownloading = true
        }
        val asset = release.apkAsset ?: return
        // DownloadManager does not reliably replace an existing destination.
        // Remove the previous installer before enqueuing a newer release.
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?.resolve("ifafu-update.apk")
            ?.delete()
        val originalUrl = asset.downloadUrl
        tryDownload(context, release, originalUrl, 0)
    }

    private fun tryDownload(context: Context, release: ReleaseInfo, originalUrl: String, mirrorIndex: Int) {
        // 后续所有长生命周期引用(receiver 注册、超时轮询闭包)必须挂在
        // applicationContext 上:调用方传入的是 Activity,慢网下载可达数十分钟,
        // 若闭包/注册持有 Activity,整个下载期间都无法回收。
        val appContext = context.applicationContext

        val url = if (mirrorIndex < mirrors.size) {
            "${mirrors[mirrorIndex]}/$originalUrl"
        } else {
            originalUrl
        }

        val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("iFAFU 更新")
            .setDescription("正在下载 v${release.versionName}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "ifafu-update.apk")
            .setMimeType("application/vnd.android.package-archive")

        val downloadId = dm.enqueue(request)

        var receiver: BroadcastReceiver? = null
        val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
        // 慢网（校园 WiFi/镜像限速）下载大 APK 常超过 10 分钟：到点直接反注册
        // receiver 的话，之后完成的下载无人处理，用户永远等不到安装界面。
        // 改为查询状态：仍在进行就续等，已完成就地安装，失败才放弃。
        val checkTimeout = object : Runnable {
            override fun run() {
                val status = queryStatus(dm, downloadId)
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        try { appContext.unregisterReceiver(receiver) } catch (_: Exception) {}
                        handleDownloadCompleted(appContext, dm, downloadId, release, originalUrl, mirrors.size)
                    }
                    DownloadManager.STATUS_PENDING,
                    DownloadManager.STATUS_RUNNING,
                    DownloadManager.STATUS_PAUSED ->
                        timeoutHandler.postDelayed(this, 10 * 60 * 1000L)
                    else -> {
                        // 失败或记录已被清除：放弃本次监听，允许用户重新发起
                        try { appContext.unregisterReceiver(receiver) } catch (_: Exception) {}
                        isDownloading = false
                    }
                }
            }
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != downloadId) return

                timeoutHandler.removeCallbacksAndMessages(null)
                try { appContext.unregisterReceiver(receiver) } catch (_: Exception) {}

                handleDownloadCompleted(ctx, dm, downloadId, release, originalUrl, mirrorIndex)
            }
        }

        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        timeoutHandler.postDelayed(checkTimeout, 10 * 60 * 1000L)
    }

    private fun queryStatus(dm: DownloadManager, downloadId: Long): Int {
        return try {
            dm.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                } else {
                    DownloadManager.STATUS_FAILED
                }
            }
        } catch (_: Exception) {
            DownloadManager.STATUS_FAILED
        }
    }

    /**
     * 下载完成后的统一出口：失败按镜像顺序重试，镜像耗尽后回退浏览器下载；
     * 成功则校验安装权限并拉起安装器。
     */
    private fun handleDownloadCompleted(
        context: Context,
        dm: DownloadManager,
        downloadId: Long,
        release: ReleaseInfo,
        originalUrl: String,
        failedMirrorIndex: Int
    ) {
        val status = queryStatus(dm, downloadId)
        if (status == DownloadManager.STATUS_FAILED) {
            dm.remove(downloadId)
            if (failedMirrorIndex < mirrors.size) {
                android.util.Log.w("UpdateChecker", "Mirror $failedMirrorIndex failed, trying next")
                tryDownload(context, release, originalUrl, failedMirrorIndex + 1)
            } else {
                android.util.Log.w("UpdateChecker", "Direct download failed, opening browser")
                isDownloading = false
                android.widget.Toast.makeText(context, "下载失败，请在浏览器中下载", android.widget.Toast.LENGTH_SHORT).show()
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(originalUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return
        }

        isDownloading = false
        if (status != DownloadManager.STATUS_SUCCESSFUL) return

        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
        val file = File(dir, "ifafu-update.apk")

        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } else {
            Uri.fromFile(file)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            android.widget.Toast.makeText(context, "请允许安装未知来源应用", android.widget.Toast.LENGTH_LONG).show()
            val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        }

        val install = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(install)
    }

    fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 * 1024 -> "${bytes / 1024}KB"
            else -> String.format("%.1fMB", bytes / 1024.0 / 1024.0)
        }
    }

    // --- Cache & dismiss methods ---

    private const val PREFS_NAME = "update_prefs"
    private const val KEY_LAST_CHECK_TS = "last_check_ts"
    private const val KEY_CACHED_TAG = "cached_tag"
    private const val KEY_CACHED_BODY = "cached_body"
    private const val KEY_CACHED_SIZE = "cached_size"
    private const val KEY_CACHED_URL = "cached_url"
    private const val KEY_DISMISSED_VERSION = "dismissed_version"
    private const val KEY_DISMISSED_UNTIL = "dismissed_until"
    private const val DISMISS_DURATION_MS = 8 * 60 * 60 * 1000L
    private const val CHECK_INTERVAL_MS = 4 * 60 * 60 * 1000L // 4 hours

    fun shouldCheck(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastTs = prefs.getLong(KEY_LAST_CHECK_TS, 0L)
        return System.currentTimeMillis() - lastTs > CHECK_INTERVAL_MS
    }

    fun markChecked(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_CHECK_TS, System.currentTimeMillis())
            .apply()
    }

    fun saveCheckResult(context: Context, release: ReleaseInfo) {
        val asset = release.apkAsset ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dismissedVersion = prefs.getString(KEY_DISMISSED_VERSION, "") ?: ""
        prefs.edit().apply {
            putLong(KEY_LAST_CHECK_TS, System.currentTimeMillis())
            putString(KEY_CACHED_TAG, release.tagName)
            putString(KEY_CACHED_BODY, release.body ?: "")
            putLong(KEY_CACHED_SIZE, asset.size)
            putString(KEY_CACHED_URL, asset.downloadUrl)
            if (dismissedVersion.isNotEmpty() && dismissedVersion != release.versionName) {
                remove(KEY_DISMISSED_VERSION)
                remove(KEY_DISMISSED_UNTIL)
            }
            apply()
        }
    }

    fun loadCachedResult(context: Context): ReleaseInfo? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val tag = prefs.getString(KEY_CACHED_TAG, null) ?: return null
        val body = prefs.getString(KEY_CACHED_BODY, "") ?: ""
        val size = prefs.getLong(KEY_CACHED_SIZE, 0L)
        val url = prefs.getString(KEY_CACHED_URL, "") ?: ""
        return ReleaseInfo(
            tagName = tag,
            body = body,
            assets = listOf(ReleaseInfo.Asset(name = "app-release.apk", downloadUrl = url, size = size))
        )
    }

    fun clearCachedResult(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            remove(KEY_CACHED_TAG)
            remove(KEY_CACHED_BODY)
            remove(KEY_CACHED_SIZE)
            remove(KEY_CACHED_URL)
            apply()
        }
    }

    fun dismissVersion(context: Context, versionName: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_DISMISSED_VERSION, versionName)
            .putLong(KEY_DISMISSED_UNTIL, System.currentTimeMillis() + DISMISS_DURATION_MS)
            .apply()
    }

    fun isDismissed(context: Context, versionName: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dismissed = prefs.getString(KEY_DISMISSED_VERSION, "") ?: ""
        val dismissedUntil = prefs.getLong(KEY_DISMISSED_UNTIL, 0L)
        return dismissed.isNotEmpty() && dismissed == versionName && dismissedUntil > System.currentTimeMillis()
    }
}
