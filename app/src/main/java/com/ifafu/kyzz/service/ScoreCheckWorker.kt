package com.ifafu.kyzz.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.api.ScoreApi
import com.ifafu.kyzz.data.cache.CacheManager
import com.ifafu.kyzz.data.model.Score
import com.ifafu.kyzz.data.repository.UserRepository
import com.ifafu.kyzz.ui.main.MainActivity
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** Performs the multi-request score sync outside BroadcastReceiver's short window. */
class ScoreCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun scoreApi(): ScoreApi
        fun cacheManager(): CacheManager
        fun userRepository(): UserRepository
    }

    override suspend fun doWork(): Result {
        return try {
            val dependencies = EntryPointAccessors.fromApplication(
                applicationContext,
                Dependencies::class.java
            )
            val userRepository = dependencies.userRepository()
            val user = userRepository.getUser()
            if (!user.isLogin || user.account.isEmpty()) return Result.success()

            val cacheManager = dependencies.cacheManager()
            val cachedScores = cacheManager.loadScores(user.account)
            val freshScores = withTimeoutOrNull(WORK_TIMEOUT_MS) {
                // The default allows automatic relogin, including captcha
                // recognition and bounded retries, inside WorkManager's window.
                dependencies.scoreApi().getAllScores(
                    userRepository.host,
                    user.token,
                    user.account,
                    user.name
                )
            } ?: return retryOnce()

            processScores(user.account, cachedScores, freshScores, cacheManager)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "后台成绩检查失败", e)
            retryOnce()
        }
    }

    private fun processScores(
        account: String,
        cachedScores: List<Score>?,
        freshScores: List<Score>,
        cacheManager: CacheManager
    ) {
        val monitorPrefs = applicationContext.getSharedPreferences("ifafu_user", Context.MODE_PRIVATE)
        val initializedKey = "score_monitor_initialized_$account"

        // No score list means there is no trustworthy new/old comparison baseline.
        if (cachedScores == null) {
            cacheManager.mergeAndAssignFirstSeen(account, freshScores)
            cacheManager.saveScores(account, freshScores)
            monitorPrefs.edit().putBoolean(initializedKey, true).apply()
            return
        }

        fun scoreKey(score: Score): Triple<String, String, String> =
            Triple(score.courseCode, score.year, score.term)

        val cachedByKey = cachedScores.associateBy(::scoreKey)
        val newScores = freshScores.filter { it.score > 0f && scoreKey(it) !in cachedByKey }
        val changedScores = freshScores.mapNotNull { fresh ->
            val old = cachedByKey[scoreKey(fresh)] ?: return@mapNotNull null
            val changed = old.score != fresh.score ||
                old.makeupScore != fresh.makeupScore ||
                old.scorePoint != fresh.scorePoint ||
                old.comment != fresh.comment ||
                old.makeupComment != fresh.makeupComment
            if (changed) old to fresh else null
        }

        cacheManager.mergeAndAssignFirstSeen(account, freshScores)
        cacheManager.saveScores(account, freshScores)

        if (newScores.isNotEmpty() || changedScores.isNotEmpty()) {
            val lines = buildList {
                newScores.forEach { add("${it.courseName}：${formatScore(it.score)}") }
                changedScores.forEach { (old, fresh) ->
                    add("${fresh.courseName}：${formatScore(old.score)} → ${formatScore(fresh.score)}")
                }
            }
            val title = when {
                newScores.isNotEmpty() && changedScores.isNotEmpty() ->
                    "${newScores.size}门新成绩，${changedScores.size}门有更新"
                newScores.isNotEmpty() -> "有${newScores.size}门新成绩"
                else -> "有${changedScores.size}门成绩已更新"
            }
            showNotification(title, lines.joinToString("\n"))
        }
        monitorPrefs.edit().putBoolean(initializedKey, true).apply()
    }

    private fun retryOnce(): Result {
        Log.w(TAG, "成绩检查未完成，runAttemptCount=$runAttemptCount")
        return if (runAttemptCount == 0) Result.retry() else Result.success()
    }

    private fun showNotification(title: String, content: String) {
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val openApp = PendingIntent.getActivity(
            applicationContext,
            1002,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    ScoreCheckReceiver.CHANNEL_ID,
                    "成绩通知",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
        val notification = NotificationCompat.Builder(
            applicationContext,
            ScoreCheckReceiver.CHANNEL_ID
        )
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(1002, notification)
    }

    private fun formatScore(score: Float): String {
        if (score <= 0f) return "暂无"
        val rounded = (score * 10f).roundToInt() / 10f
        return if (rounded == rounded.toInt().toFloat()) rounded.toInt().toString()
        else "%.1f".format(rounded)
    }

    companion object {
        private const val TAG = "ScoreCheckWorker"
        private const val WORK_TIMEOUT_MS = 3 * 60 * 1000L
    }
}
