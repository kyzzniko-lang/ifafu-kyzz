package com.ifafu.kyzz.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.preference.PreferenceManager
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
            val snapshot = userRepository.getAuthSnapshot()
            val user = snapshot.user
            if (!user.isLogin || user.account.isEmpty() || !notificationsEnabled()) return Result.success()

            val cacheManager = dependencies.cacheManager()
            val cacheSnapshot = cacheManager.getScoresSnapshot(user.account)
            val freshScores = withTimeoutOrNull(WORK_TIMEOUT_MS) {
                // The default allows automatic relogin, including captcha
                // recognition and bounded retries, inside WorkManager's window.
                dependencies.scoreApi().getAllScores(
                    snapshot.host,
                    user.token,
                    user.account,
                    user.name
                )
            } ?: return retryOnce()

            // 拉取期间用户可能已退出或切换账号。旧任务继续写缓存并弹通知会把
            // 上一个学生的成绩显示给当前用户；账号世代变化时静默丢弃本次结果。
            currentCoroutineContext().ensureActive()
            if (!userRepository.isSessionCurrent(snapshot) || !notificationsEnabled()) {
                Log.i(TAG, "账号在成绩检查期间发生变化，丢弃旧账号结果")
                return Result.success()
            }

            userRepository.runIfSessionCurrent(snapshot) {
                if (notificationsEnabled() && !isStopped) {
                    processScores(user.account, cacheSnapshot.revision, freshScores, cacheManager)
                }
            }
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
        expectedCacheRevision: Long,
        freshScores: List<Score>,
        cacheManager: CacheManager
    ) {
        // 教务系统改版或返回错误页时，页面"正常"但解析出 0 门成绩。已有成绩的老用户
        // 突然一条不剩几乎必然是解析失败而非真实数据：此时若把缓存覆盖为空，下次
        // 成功拉取会把全部历史成绩（四年可达 50+ 门）误判为新出分并弹出巨型通知。
        // 无缓存时同样不落盘空基线——空列表会让下轮 diff 把全部课程误报为新出分。
        if (freshScores.isEmpty()) {
            Log.w(TAG, "本次拉取到 0 门成绩，保留缓存及通知基线")
            return
        }

        // The foreground may refresh the same account while the request is in flight.
        // Compare and commit under CacheManager's lock, including an originally absent
        // cache (revision 0); never overwrite a newer response or diff an old baseline.
        val committed = cacheManager.commitFreshScoresIfUnchanged(account, freshScores, expectedCacheRevision)
        if (committed == null) {
            Log.i(TAG, "成绩缓存已由其他请求更新，丢弃较早发起的后台响应")
            return
        }
        val cachedScores = committed.first ?: return // First trustworthy baseline: do not notify history.
        val effectiveScores = committed.second

        // 与 CacheManager.scoreMergeKey 同一实现:空课程代码的记录不得互相碰撞
        fun scoreKey(score: Score): Triple<String, String, String> = CacheManager.scoreMergeKey(score)

        val cachedByKey = cachedScores.associateBy(::scoreKey)
        val newScores = effectiveScores.filter { it.score > 0f && scoreKey(it) !in cachedByKey }
        val changedScores = effectiveScores.mapNotNull { fresh ->
            val old = cachedByKey[scoreKey(fresh)] ?: return@mapNotNull null
            val changed = old.score != fresh.score ||
                old.makeupScore != fresh.makeupScore ||
                old.scorePoint != fresh.scorePoint ||
                old.comment != fresh.comment ||
                old.makeupComment != fresh.makeupComment
            if (changed) old to fresh else null
        }

        if (newScores.isNotEmpty() || changedScores.isNotEmpty()) {
            val lines = buildList {
                newScores.forEach { add("${it.courseName}：${formatScore(it.score)}") }
                changedScores.forEach { (old, fresh) ->
                    // 总评没变仅补考分变化时，显示"40 → 40"毫无信息量，改报补考分
                    if (old.score == fresh.score && old.makeupScore != fresh.makeupScore) {
                        add("${fresh.courseName}：补考 ${formatScore(old.makeupScore)} → ${formatScore(fresh.makeupScore)}")
                    } else {
                        add("${fresh.courseName}：${formatScore(old.score)} → ${formatScore(fresh.score)}")
                    }
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
    }

    private fun retryOnce(): Result {
        Log.w(TAG, "成绩检查未完成，runAttemptCount=$runAttemptCount")
        return if (runAttemptCount == 0) Result.retry() else Result.success()
    }

    private fun notificationsEnabled(): Boolean =
        PreferenceManager.getDefaultSharedPreferences(applicationContext)
            .getBoolean("notify_score", true)

    private fun showNotification(title: String, content: String) {
        if (!notificationsEnabled() || isStopped) return
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Android 13+ 通知权限被拒时 notify() 是静默 no-op，提前跳过
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()
        ) {
            return
        }
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
