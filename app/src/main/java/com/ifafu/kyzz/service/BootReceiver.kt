package com.ifafu.kyzz.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Calendar

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        // 接受开机完成和应用更新完成两种广播。
        // MY_PACKAGE_REPLACED：App 升级后闹钟会被清除，需重新注册，否则每日提醒静默失效。
        val action = intent?.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val prefs = context.getSharedPreferences("ifafu_user", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("isLogin", false)) return

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        run {
            // Course reminder at 7:30 AM daily
            val courseIntent = Intent(context, CourseReminderReceiver::class.java)
            val coursePending = PendingIntent.getBroadcast(
                context, 0, courseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val courseCal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 7); set(Calendar.MINUTE, 30); set(Calendar.SECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
            }
            try {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(courseCal.timeInMillis, null), coursePending)
            } catch (e: SecurityException) {
                // Android 14+ 默认不授予精确闹钟权限；降级为非精确闹钟，保证提醒不静默失效
                Log.w("BootReceiver", "无精确闹钟权限，课程提醒降级为非精确闹钟", e)
                try {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, courseCal.timeInMillis, coursePending)
                } catch (e2: Exception) {
                    Log.w("BootReceiver", "课程提醒闹钟注册失败", e2)
                }
            }
        }

        run {
            // Score check at 12:00 PM daily
            val scoreIntent = Intent(context, ScoreCheckReceiver::class.java)
            val scorePending = PendingIntent.getBroadcast(
                context, 1, scoreIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val scoreCal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
            }
            try {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(scoreCal.timeInMillis, null), scorePending)
            } catch (e: SecurityException) {
                // Android 14+ 默认不授予精确闹钟权限；降级为非精确闹钟，保证成绩检查不静默失效
                Log.w("BootReceiver", "无精确闹钟权限，成绩检查降级为非精确闹钟", e)
                try {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, scoreCal.timeInMillis, scorePending)
                } catch (e2: Exception) {
                    Log.w("BootReceiver", "成绩检查闹钟注册失败", e2)
                }
            }
        }

        try {
            // 每天按课程逐节挂的"临近上课"精确闹钟是一次性的，重启/升级后被清空
            // 就不会补挂（例如 10:00 重启，10:30 的上课提醒直接丢失）。
            // 触发一次 reschedule-only 的课程提醒广播，把今天剩余课程的闹钟补回来。
            val reschedule = Intent(context, CourseReminderReceiver::class.java).apply {
                putExtra(CourseReminderReceiver.EXTRA_RESCHEDULE_ONLY, true)
            }
            context.sendBroadcast(reschedule)
        } catch (e: Exception) {
            Log.w("BootReceiver", "补挂课程提醒失败", e)
        }
    }
}
