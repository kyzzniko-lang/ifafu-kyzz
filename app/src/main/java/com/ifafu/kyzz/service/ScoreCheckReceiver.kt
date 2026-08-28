package com.ifafu.kyzz.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar

/** Alarm entry point. Network work is delegated to WorkManager immediately. */
class ScoreCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // 未登录（登出/切号后）不再自续闹钟链：scheduleNext 无条件重排会让
        // setAlarmClock 每天唤醒设备并在状态栏常驻图标，登出后也不会停止。
        val userPrefs = context.getSharedPreferences("ifafu_user", Context.MODE_PRIVATE)
        if (!userPrefs.getBoolean("isLogin", false)) {
            Log.i(TAG, "用户未登录，停止成绩检查闹钟链")
            cancel(context)
            return
        }
        val shouldNotify = PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean("notify_score", true)
        if (shouldNotify) enqueueCheck(context)
        ensureScheduled(context)
    }

    private fun enqueueCheck(context: Context) {
        val request = OneTimeWorkRequestBuilder<ScoreCheckWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    companion object {
        const val CHANNEL_ID = "score_notification"
        private const val TAG = "ScoreCheckReceiver"
        private const val UNIQUE_WORK_NAME = "score_check"

        /**
         * 挂每日 12:00 的成绩检查闹钟（自续链起点）。幂等：requestCode 固定，
         * 重复调用覆盖同一 PendingIntent。BootReceiver 与 MainViewModel 登录态
         * 重挂都走这里。
         */
        fun ensureScheduled(context: Context) {
            try {
                val pending = PendingIntent.getBroadcast(
                    context,
                    1,
                    Intent(context, ScoreCheckReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val triggerAt = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 12)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    // 开机补挂/上午重新登录时今天 12 点还没到，先挂今天的；
                    // 已过 12 点（含广播自续场景）则挂明天
                    if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
                }.timeInMillis
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, null), pending)
            } catch (e: SecurityException) {
                scheduleFallback(context)
                Log.w(TAG, "无精确闹钟权限，降级为非精确闹钟", e)
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
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }

        /** 登出时取消每日成绩检查闹钟（requestCode 与 ensureScheduled 一致），拆断自续链。 */
        fun cancel(context: Context) {
            try {
                val pending = PendingIntent.getBroadcast(
                    context, 1, Intent(context, ScoreCheckReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.cancel(pending)
                pending.cancel()
            } catch (e: Exception) {
                Log.w(TAG, "取消成绩检查闹钟失败", e)
            }
        }
    }
}
