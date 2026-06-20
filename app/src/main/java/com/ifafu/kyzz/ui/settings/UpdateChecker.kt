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
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

object UpdateChecker {

    private const val REPO = "kyzzniko-lang/ifafu-kyzz"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"

    private val checkScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

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
        val apkAsset: Asset? get() = assets?.find { it.name.endsWith(".apk") }
    }

    fun checkForUpdate(context: Context, callback: (ReleaseInfo?) -> Unit) {
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        checkScope.launch {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()

                android.util.Log.i("UpdateChecker", "Checking: $API_URL")
                val request = Request.Builder()
                    .url(API_URL)
                    .header("Accept", "application/vnd.github.v3+json")
                    .apply {
                        val token = com.ifafu.kyzz.data.util.KeyGuard.decode(com.ifafu.kyzz.BuildConfig.GITHUB_TOKEN_ENC)
                        if (token.isNotEmpty()) {
                            header("Authorization", "token $token")
                        }
                    }
                    .build()

                client.newCall(request).execute().use { response ->
                    android.util.Log.i("UpdateChecker", "Response code: ${response.code}")
                    if (!response.isSuccessful) {
                        android.util.Log.w("UpdateChecker", "Non-success: ${response.code}")
                        mainHandler.post { callback(null) }
                        return@launch
                    }

                    val body = response.body?.string() ?: run {
                        android.util.Log.w("UpdateChecker", "Empty body")
                        mainHandler.post { callback(null) }
                        return@launch
                    }

                    val release = Gson().fromJson(body, ReleaseInfo::class.java)
                    val currentVersion = getCurrentVersion(context)
                    val isNewer = isNewerVersion(release.versionName, currentVersion)
                    val hasApk = release.apkAsset != null
                    android.util.Log.i("UpdateChecker", "Remote: ${release.versionName}, Local: $currentVersion, isNewer: $isNewer, hasApk: $hasApk, APK: ${release.apkAsset?.name}")

                    if (isNewer && hasApk) {
                        mainHandler.post { callback(release) }
                    } else {
                        mainHandler.post { callback(null) }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 协程取消，不回调
            } catch (e: Exception) {
                android.util.Log.e("UpdateChecker", "Check failed: ${e.javaClass.simpleName}: ${e.message}")
                mainHandler.post { callback(null) }
            }
        }
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
        val latestParts = latest.split(".").map { it.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        val currentParts = current.split(".").map { it.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 }
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

    fun downloadAndInstall(context: Context, release: ReleaseInfo) {
        if (isDownloading) return
        val asset = release.apkAsset ?: return
        isDownloading = true
        val originalUrl = asset.downloadUrl
        tryDownload(context, release, originalUrl, 0)
    }

    private fun tryDownload(context: Context, release: ReleaseInfo, originalUrl: String, mirrorIndex: Int) {
        val url = if (mirrorIndex < mirrors.size) {
            "${mirrors[mirrorIndex]}/$originalUrl"
        } else {
            originalUrl
        }

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("iFAFU 更新")
            .setDescription("正在下载 v${release.versionName}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "ifafu-update.apk")
            .setMimeType("application/vnd.android.package-archive")

        val downloadId = dm.enqueue(request)

        var receiver: BroadcastReceiver? = null
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != downloadId) return

                try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}

                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = dm.query(query)
                val status = if (cursor.moveToFirst()) {
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                } else {
                    cursor.close()
                    DownloadManager.STATUS_FAILED
                }
                cursor.close()

                if (status == DownloadManager.STATUS_FAILED) {
                    dm.remove(downloadId)
                    if (mirrorIndex < mirrors.size) {
                        android.util.Log.w("UpdateChecker", "Mirror $mirrorIndex failed, trying next")
                        tryDownload(ctx, release, originalUrl, mirrorIndex + 1)
                    } else if (mirrorIndex == mirrors.size) {
                        // Direct GitHub also failed, open browser
                        android.util.Log.w("UpdateChecker", "Direct download failed, opening browser")
                        isDownloading = false
                        android.widget.Toast.makeText(ctx, "下载失败，请在浏览器中下载", android.widget.Toast.LENGTH_SHORT).show()
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(originalUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } else {
                        isDownloading = false
                    }
                    return
                }

                isDownloading = false
                if (status != DownloadManager.STATUS_SUCCESSFUL) return

                val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
                val file = File(dir, "ifafu-update.apk")

                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
                } else {
                    Uri.fromFile(file)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ctx.packageManager.canRequestPackageInstalls()) {
                    android.widget.Toast.makeText(ctx, "请允许安装未知来源应用", android.widget.Toast.LENGTH_LONG).show()
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${ctx.packageName}"))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    return
                }

                val install = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(install)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            context.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        }

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            isDownloading = false
        }, 10 * 60 * 1000L)
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
    private const val CHECK_INTERVAL_MS = 4 * 60 * 60 * 1000L // 4 hours

    fun shouldCheck(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastTs = prefs.getLong(KEY_LAST_CHECK_TS, 0L)
        return System.currentTimeMillis() - lastTs > CHECK_INTERVAL_MS
    }

    fun saveCheckResult(context: Context, release: ReleaseInfo) {
        val asset = release.apkAsset ?: return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putLong(KEY_LAST_CHECK_TS, System.currentTimeMillis())
            putString(KEY_CACHED_TAG, release.tagName)
            putString(KEY_CACHED_BODY, release.body ?: "")
            putLong(KEY_CACHED_SIZE, asset.size)
            putString(KEY_CACHED_URL, asset.downloadUrl)
            remove(KEY_DISMISSED_VERSION)
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
            .apply()
    }

    fun isDismissed(context: Context, versionName: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dismissed = prefs.getString(KEY_DISMISSED_VERSION, "") ?: ""
        return dismissed.isNotEmpty() && dismissed == versionName
    }
}
