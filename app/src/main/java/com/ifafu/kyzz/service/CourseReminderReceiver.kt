package com.ifafu.kyzz.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.cache.CacheManager
import com.ifafu.kyzz.data.repository.UserRepository
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class CourseReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val pendingResult = goAsync()
        Thread {
            try {
                val prefs = context.getSharedPreferences("ifafu_user", Context.MODE_PRIVATE)
                val shouldNotify = prefs.getBoolean("notify_course", true)

                if (shouldNotify) {
                    val text = buildReminderText(context)
                    if (text.isNotEmpty()) {
                        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val channel = NotificationChannel(
                                CHANNEL_ID, "课程提醒", NotificationManager.IMPORTANCE_DEFAULT
                            )
                            nm.createNotificationChannel(channel)
                        }

                        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                            .setSmallIcon(R.mipmap.ic_launcher)
                            .setContentTitle("今日课程提醒")
                            .setContentText(text)
                            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                            .setAutoCancel(true)
                            .build()

                        nm.notify(1001, notification)
                    }
                }

                // Always re-schedule for next day to keep the alarm chain alive
                scheduleNext(context)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    private fun scheduleNext(context: Context) {
        try {
            val intent = Intent(context, CourseReminderReceiver::class.java)
            val pending = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 7); set(Calendar.MINUTE, 30); set(Calendar.SECOND, 0)
                add(Calendar.DAY_OF_YEAR, 1)
            }
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setAlarmClock(AlarmManager.AlarmClockInfo(cal.timeInMillis, null), pending)
        } catch (e: SecurityException) {
            android.util.Log.w("CourseReminderReceiver", "无精确闹钟权限", e)
        }
    }

    private fun buildReminderText(context: Context): String {
        try {
            val app = context.applicationContext
            val userRepo = UserRepository(app)
            val user = userRepo.getUser()
            val firstDay = userRepo.termFirstDay
            if (!user.isLogin || firstDay.isEmpty()) return ""

            val cacheManager = CacheManager(app)
            val syllabus = cacheManager.loadSyllabus(user.account) ?: return ""

            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val parsed = sdf.parse(firstDay) ?: return ""
            val termStart = Calendar.getInstance().apply { time = parsed }
            val today = Calendar.getInstance()
            val diffDays = ((today.timeInMillis - termStart.timeInMillis) / (24 * 60 * 60 * 1000)).toInt()
            val currentWeek = (diffDays / 7) + 1
            val dayOfWeek = today.get(Calendar.DAY_OF_WEEK)
            val todayDay = if (dayOfWeek == Calendar.SUNDAY) 7 else dayOfWeek - 1

            val matched = syllabus.courses.filter { c ->
                currentWeek in c.weekBegin..c.weekEnd && c.weekDay == todayDay &&
                    (c.oddOrTwice == 0 || (c.oddOrTwice == 1 && currentWeek % 2 == 1) || (c.oddOrTwice == 2 && currentWeek % 2 == 0))
            }.sortedBy { it.begin }

            if (matched.isEmpty()) return "今天没有课程，好好休息~"

            return matched.joinToString("\n") { c ->
                "第${c.begin}-${c.end}节 ${c.name} @${c.address}"
            }
        } catch (_: Exception) {
            return ""
        }
    }

    companion object {
        const val CHANNEL_ID = "course_reminder"
    }
}
