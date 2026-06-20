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
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.api.ScoreApi
import com.ifafu.kyzz.data.cache.CacheManager
import com.ifafu.kyzz.data.repository.UserRepository
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
        val prefs = context.getSharedPreferences("ifafu_user", Context.MODE_PRIVATE)
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
            val lastCount = cachedScores?.size ?: 0

            val freshScores = withContext(Dispatchers.IO) {
                scoreApi.getAllScores(
                    userRepository.host, user.token, user.account, user.name
                )
            }

            if (freshScores != null) {
                cacheManager.saveScores(user.account, freshScores)
                val currentCount = freshScores.size

                // 只有当之前有缓存数据（非首次加载）且新数据更多时才通知
                // 避免学期变更后缓存被清除导致的误报
                if (lastCount > 0 && currentCount > lastCount) {
                    val newCount = currentCount - lastCount
                    // 通过比较课程名确认是真正的新成绩，而非缓存恢复
                    val cachedNames = cachedScores?.map { it.courseName to it.year to it.term }?.toSet() ?: emptySet()
                    val trulyNew = freshScores.filter { (it.courseName to it.year to it.term) !in cachedNames }
                    if (trulyNew.isNotEmpty()) {
                        val names = trulyNew.joinToString("、") { it.courseName }
                        showNotification(context, "有${trulyNew.size}门新成绩", names)
                    }
                }
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
            Log.w("ScoreCheckReceiver", "无精确闹钟权限", e)
        }
    }

    private fun showNotification(context: Context, title: String, content: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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
            .setAutoCancel(true)
            .build()
        nm.notify(1002, notification)
    }

    companion object {
        const val CHANNEL_ID = "score_notification"
    }
}