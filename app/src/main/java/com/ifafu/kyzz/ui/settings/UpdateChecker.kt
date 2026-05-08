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
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"

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

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    callback(null)
                    return@Thread
                }

                val body = response.body?.string() ?: run {
                    callback(null)
                    return@Thread
                }

                val release = Gson().fromJson(body, ReleaseInfo::class.java)
                val currentVersion = getCurrentVersion(context)

                if (isNewerVersion(release.versionName, currentVersion) && release.apkAsset != null) {
                    callback(release)
                } else {
                    callback(null)
                }
            } catch (_: Exception) {
                callback(null)
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
        val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
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

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(asset.downloadUrl))
            .setTitle("iFAFU 更新")
            .setDescription("正在下载 v${release.versionName}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "ifafu-update.apk")
            .setMimeType("application/vnd.android.package-archive")

        val downloadId = dm.enqueue(request)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != downloadId) return

                context.unregisterReceiver(this)

                val file = File(
                    ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                    "ifafu-update.apk"
                )

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
    }

    fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 * 1024 -> "${bytes / 1024}KB"
            else -> String.format("%.1fMB", bytes / 1024.0 / 1024.0)
        }
    }
}
