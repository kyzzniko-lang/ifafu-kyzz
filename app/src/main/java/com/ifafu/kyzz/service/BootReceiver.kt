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

        try {
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
            am.setAlarmClock(AlarmManager.AlarmClockInfo(courseCal.timeInMillis, null), coursePending)
        } catch (e: SecurityException) {
            Log.w("BootReceiver", "无精确闹钟权限，跳过课程提醒", e)
        }

        try {
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
            am.setAlarmClock(AlarmManager.AlarmClockInfo(scoreCal.timeInMillis, null), scorePending)
        } catch (e: SecurityException) {
            Log.w("BootReceiver", "无精确闹钟权限，跳过成绩检查", e)
        }
    }
}
