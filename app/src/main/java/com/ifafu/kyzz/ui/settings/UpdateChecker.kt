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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

object UpdateChecker {

    private const val REPO = "kyzzniko-lang/ifafu-kyzz"
    private const val API_URL = "https://gh-proxy.com/https://api.github.com/repos/$REPO/releases/latest"

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
        Thread {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url(API_URL)
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post { callback(null) }
                        return@Thread
                    }

                    val body = response.body?.string() ?: run {
                        android.os.Handler(android.os.Looper.getMainLooper()).post { callback(null) }
                        return@Thread
                    }

                    val release = Gson().fromJson(body, ReleaseInfo::class.java)
                    val currentVersion = getCurrentVersion(context)
                    android.util.Log.i("UpdateChecker", "Remote: ${release.versionName}, Local: $currentVersion, APK: ${release.apkAsset?.name}")

                    if (isNewerVersion(release.versionName, currentVersion) && release.apkAsset != null) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post { callback(release) }
                    } else {
                        android.os.Handler(android.os.Looper.getMainLooper()).post { callback(null) }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("UpdateChecker", "Check failed: ${e.message}", e)
                android.os.Handler(android.os.Looper.getMainLooper()).post { callback(null) }
            }
        }.start()
    }

    private fun getCurrentVersion(context: Context): String {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: "0.0.0"
        } catch (_: PackageManager.NameNotFoundException) {
            "0.0.0"
        }
    }

    private fun isNewerVersion(latest: String, current: String): Boolean {
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

    fun downloadAndInstall(context: Context, release: ReleaseInfo) {
        val asset = release.apkAsset ?: return
        val url = if (asset.downloadUrl.contains("github.com")) {
            "https://gh-proxy.com/${asset.downloadUrl}"
        } else {
            asset.downloadUrl
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

                val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
                val file = File(dir, "ifafu-update.apk")

                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
                } else {
                    Uri.fromFile(file)
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

        // Safety net: auto-unregister after 10 minutes to prevent leak
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
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
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24 hours

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
        return prefs.getString(KEY_DISMISSED_VERSION, "") == versionName
    }
}
