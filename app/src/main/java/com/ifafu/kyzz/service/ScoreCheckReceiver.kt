package com.ifafu.kyzz.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.api.ScoreApi
import com.ifafu.kyzz.data.cache.CacheManager
import com.ifafu.kyzz.data.repository.UserRepository
import com.ifafu.kyzz.ui.main.MainActivity
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import java.util.Calendar

class ScoreCheckReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ScoreCheckEntryPoint {
        fun scoreApi(): ScoreApi
        fun cacheManager(): CacheManager
        fun userRepository(): UserRepository
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val shouldNotify = prefs.getBoolean("notify_score", true)

        if (shouldNotify) {
            val pendingResult = goAsync()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope.launch {
                try {
                    checkNewScores(context)
                } finally {
                    scope.cancel()
                    pendingResult.finish()
                }
            }
        }

        scheduleNext(context)
    }

    private suspend fun checkNewScores(context: Context) {
        try {
            val appContext = context.applicationContext
            val entryPoint = EntryPointAccessors.fromApplication(
                appContext, ScoreCheckEntryPoint::class.java
            )
            val userRepository = entryPoint.userRepository()
            val cacheManager = entryPoint.cacheManager()
            val scoreApi = entryPoint.scoreApi()

            val user = userRepository.getUser()
            if (!user.isLogin || user.account.isEmpty()) return

            val cachedScores = cacheManager.loadScores(user.account)

            // goAsync() 的 PendingResult 有 ~10 秒硬限制，超时系统会强杀广播。
            // 教务系统多轮 HTTP（含 relogin）可能超过该窗口，用 8s 超时兜底，
            // 留 2s 给 finally 里的 finish()。超时则本次跳过，不影响下次定时。
            val freshScores = withTimeoutOrNull(SCORE_CHECK_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    scoreApi.getAllScores(
                        userRepository.host, user.token, user.account, user.name
                    )
                }
            }
            if (freshScores == null) {
                Log.w("ScoreCheckReceiver", "成绩检查超时(${SCORE_CHECK_TIMEOUT_MS}ms)，跳过本次")
            }

            if (freshScores != null) {
                val monitorPrefs = context.getSharedPreferences("ifafu_user", Context.MODE_PRIVATE)
                val initializedKey = "score_monitor_initialized_${user.account}"

                // 首次启用只建立历史基线，不把全部已有成绩当作"刚出分"。
                // 基线信号同时看 initialized 标志 与 score_first_seen 表：
                // score_first_seen 表会被 clearCache()（学期切换/登出/手动清缓存）清掉，
                // 而旧 initialized 标志存放在另一个 prefs 文件不会被清——
                // 这会导致清缓存后首次检查 cachedScores==null、cachedKeys 空，
                // 从而把所有历史成绩误报为"新出分"。两个信号任一缺失都视为未建立基线。
                if (cachedScores == null &&
                    (!monitorPrefs.getBoolean(initializedKey, false) ||
                        !cacheManager.hasScoreFirstSeenBaseline(user.account))
                ) {
                    cacheManager.mergeAndAssignFirstSeen(user.account, freshScores)
                    cacheManager.saveScores(user.account, freshScores)
                    monitorPrefs.edit().putBoolean(initializedKey, true).apply()
                    return
                }

                cacheManager.mergeAndAssignFirstSeen(user.account, freshScores)
                cacheManager.saveScores(user.account, freshScores)

                // 用集合差判断新成绩，而非"计数差 + lastCount>0"。
                // 旧逻辑在学期切换清缓存后 lastCount=0 会直接漏报新学期第一条成绩，
                // 且按课程名去重在重修/同名课时会误判。改用 courseCode+year+term 稳定 key。
                fun scoreKey(s: com.ifafu.kyzz.data.model.Score): Triple<String, String, String> =
                    Triple(s.courseCode, s.year, s.term)

                val cachedByKey = cachedScores?.associateBy(::scoreKey).orEmpty()
                val cachedKeys = cachedByKey.keys
                val trulyNew = freshScores.filter { it.score > 0f && scoreKey(it) !in cachedKeys }
                val changed = freshScores.mapNotNull { fresh ->
                    val old = cachedByKey[scoreKey(fresh)] ?: return@mapNotNull null
                    val changedValue = old.score != fresh.score ||
                        old.makeupScore != fresh.makeupScore ||
                        old.scorePoint != fresh.scorePoint ||
                        old.comment != fresh.comment ||
                        old.makeupComment != fresh.makeupComment
                    if (changedValue) old to fresh else null
                }

                if (trulyNew.isNotEmpty() || changed.isNotEmpty()) {
                    val lines = buildList {
                        trulyNew.forEach { add("${it.courseName}：${formatScore(it.score)}") }
                        changed.forEach { (old, fresh) ->
                            add("${fresh.courseName}：${formatScore(old.score)} → ${formatScore(fresh.score)}")
                        }
                    }
                    val title = when {
                        trulyNew.isNotEmpty() && changed.isNotEmpty() ->
                            "${trulyNew.size}门新成绩，${changed.size}门有更新"
                        trulyNew.isNotEmpty() -> "有${trulyNew.size}门新成绩"
                        else -> "有${changed.size}门成绩已更新"
                    }
                    showNotification(context, title, lines.joinToString("\n"))
                }
                monitorPrefs.edit().putBoolean(initializedKey, true).apply()
            }
        } catch (e: Exception) {
            Log.w("ScoreCheckReceiver", "检查成绩失败", e)
        }
    }

    private fun scheduleNext(context: Context) {
        try {
            val intent = Intent(context, ScoreCheckReceiver::class.java)
            val pending = PendingIntent.getBroadcast(
                context, 1, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
                add(Calendar.DAY_OF_YEAR, 1)
            }
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setAlarmClock(AlarmManager.AlarmClockInfo(cal.timeInMillis, null), pending)
        } catch (e: SecurityException) {
            scheduleFallback(context)
            Log.w("ScoreCheckReceiver", "无精确闹钟权限", e)
        }
    }

    private fun scheduleFallback(context: Context) {
        val pending = PendingIntent.getBroadcast(
            context, 1, Intent(context, ScoreCheckReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
    }

    private fun showNotification(context: Context, title: String, content: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val openApp = PendingIntent.getActivity(
            context, 1002, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "成绩通知", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        nm.notify(1002, notification)
    }

    private fun formatScore(score: Float): String =
        if (score <= 0f) {
            "暂无"
        } else {
            // 旧实现用 score % 1f == 0f 判整数，浮点严格相等不可靠：爬取的分数经运算
            // 可能变成 84.99999f，既不满足 == 0f 又走 score.toString() 显示一长串。
            // 改为先四舍五入到一位小数再判断是否整数。
            val rounded = (score * 10f).roundToInt() / 10f
            if (rounded == rounded.toInt().toFloat()) rounded.toInt().toString()
            else "%.1f".format(rounded)
        }

    companion object {
        const val CHANNEL_ID = "score_notification"
        /** goAsync() 窗口约 10s，这里留 8s 给网络、2s 给收尾。 */
        private const val SCORE_CHECK_TIMEOUT_MS = 8_000L
    }
}
