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
        val shouldNotify = PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean("notify_score", true)
        if (shouldNotify) enqueueCheck(context)
        scheduleNext(context)
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

    private fun scheduleNext(context: Context) {
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
                add(Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, null), pending)
        } catch (e: SecurityException) {
            scheduleFallback(context)
            Log.w(TAG, "无精确闹钟权限", e)
        }
    }

    private fun scheduleFallback(context: Context) {
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
            add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
    }

    companion object {
        const val CHANNEL_ID = "score_notification"
        private const val TAG = "ScoreCheckReceiver"
        private const val UNIQUE_WORK_NAME = "score_check"
    }
}
